package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.exception.AnalysisExtractionNotAllowedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ConfirmedDocumentTypeInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionAlreadyConfirmedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionReviewException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionConfirmation;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Confirms the two reviewed drafts and advances workflow state in one owner-scoped transaction. */
@Service
public class ConfirmExtractionService {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final Clock clock;

    public ConfirmExtractionService(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void confirm(UUID analysisId, AnalysisOwner owner, ExtractionConfirmation confirmation) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(confirmation, "confirmation must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        if (analysis.status() == AnalysisStatus.MATCHING) {
            throw new ExtractionAlreadyConfirmedException();
        }
        if (analysis.status() != AnalysisStatus.AWAITING_CONFIRMATION) {
            throw new AnalysisExtractionNotAllowedException();
        }

        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId)
                .filter(current -> current.status() == AnalysisJobStatus.WAITING_FOR_USER
                        && current.currentStage() == AnalysisStatus.AWAITING_CONFIRMATION)
                .orElseThrow(ExtractionConflictException::new);
        Instant transitionAt = latest(now, analysis.updatedAt(), job.updatedAt());

        ExtractionDocuments documents = requireDocuments(analysis, transitionAt);
        requireRequestedPair(confirmation, documents);
        requireConfirmedTypes(documents);

        PersistedExtraction reference = extractedDocumentRepository
                .findByDocumentId(documents.reference().id()).orElse(null);
        PersistedExtraction invoice = extractedDocumentRepository
                .findByDocumentId(documents.invoice().id()).orElse(null);
        requireDraftPair(reference, invoice, confirmation);

        PersistedExtraction confirmedReference = confirm(reference, transitionAt);
        PersistedExtraction confirmedInvoice = confirm(invoice, transitionAt);
        Analysis matchingAnalysis = matchingAnalysis(
                analysis, documents, reference, invoice, transitionAt);
        AnalysisJob matchingJob = job.waitForMatching(transitionAt);
        if (extractedDocumentRepository.confirm(confirmedReference, transitionAt).isEmpty()
                || extractedDocumentRepository.confirm(confirmedInvoice, transitionAt).isEmpty()) {
            throw new ExtractionConflictException();
        }

        analysisRepository.update(matchingAnalysis);
        analysisJobRepository.update(matchingJob);
    }

    private ExtractionDocuments requireDocuments(Analysis analysis, Instant now) {
        List<Document> documents = documentRepository.findByAnalysisId(analysis.id()).stream()
                .sorted(ROLE_ORDER)
                .toList();
        if (documents.size() != 2
                || documents.get(0).role() != DocumentRole.REFERENCE
                || documents.get(1).role() != DocumentRole.INVOICE) {
            throw new DocumentsRequiredException();
        }
        Document reference = documents.get(0);
        Document invoice = documents.get(1);
        if (!Objects.equals(reference.expiresAt(), analysis.expiresAt())
                || !Objects.equals(invoice.expiresAt(), analysis.expiresAt())
                || expired(reference, now)
                || expired(invoice, now)) {
            throw new ExtractionConflictException();
        }
        return new ExtractionDocuments(reference, invoice);
    }

    private static void requireRequestedPair(
            ExtractionConfirmation confirmation,
            ExtractionDocuments documents) {
        if (!documents.reference().id().equals(confirmation.referenceDocumentId())
                || !documents.invoice().id().equals(confirmation.invoiceDocumentId())) {
            throw new ExtractionConflictException();
        }
    }

    private static void requireConfirmedTypes(ExtractionDocuments documents) {
        if (documents.reference().confirmedType() == null || documents.invoice().confirmedType() == null) {
            throw new ConfirmedDocumentTypeInvalidException();
        }
    }

    private static void requireDraftPair(
            PersistedExtraction reference,
            PersistedExtraction invoice,
            ExtractionConfirmation confirmation) {
        if (reference == null && invoice == null) {
            throw new ExtractionNotFoundException();
        }
        if (reference == null || invoice == null) {
            throw new ExtractionConflictException();
        }
        if (reference.extraction().status() == ExtractionStatus.CONFIRMED
                || invoice.extraction().status() == ExtractionStatus.CONFIRMED) {
            if (reference.extraction().status() == ExtractionStatus.CONFIRMED
                    && invoice.extraction().status() == ExtractionStatus.CONFIRMED) {
                throw new ExtractionAlreadyConfirmedException();
            }
            throw new ExtractionConflictException();
        }
        if (reference.extraction().status() != ExtractionStatus.DRAFT
                || invoice.extraction().status() != ExtractionStatus.DRAFT
                || reference.extraction().confirmedAt() != null
                || invoice.extraction().confirmedAt() != null) {
            throw new ExtractionConflictException();
        }
        if (reference.version() != confirmation.referenceExpectedVersion()
                || invoice.version() != confirmation.invoiceExpectedVersion()) {
            throw new ExtractionConflictException();
        }
        if (reference.extraction().lines().isEmpty() || invoice.extraction().lines().isEmpty()) {
            throw new InvalidExtractionReviewException();
        }
    }

    private static PersistedExtraction confirm(PersistedExtraction persisted, Instant confirmedAt) {
        try {
            return persisted.withExtraction(persisted.extraction().confirm(confirmedAt));
        } catch (IllegalArgumentException invalidExtraction) {
            throw new InvalidExtractionReviewException();
        }
    }

    private static Analysis matchingAnalysis(
            Analysis analysis,
            ExtractionDocuments documents,
            PersistedExtraction reference,
            PersistedExtraction invoice,
            Instant confirmedAt) {
        try {
            return analysis.confirmExtraction(
                    documents.reference().confirmedType().name(),
                    reference.extraction().documentNumber(),
                    invoice.extraction().documentNumber(),
                    preferred(invoice.extraction().vendorName(), reference.extraction().vendorName()),
                    preferred(invoice.extraction().currency(), reference.extraction().currency()),
                    reference.extraction().total(),
                    invoice.extraction().total(),
                    confirmedAt);
        } catch (IllegalArgumentException invalidSummary) {
            throw new InvalidExtractionReviewException();
        }
    }

    private static boolean expired(Document document, Instant now) {
        return document.expiresAt() != null && !document.expiresAt().isAfter(now);
    }

    private static String preferred(String primary, String fallback) {
        return primary != null ? primary : fallback;
    }

    private static Instant latest(Instant first, Instant second, Instant third) {
        return first.isAfter(second)
                ? (first.isAfter(third) ? first : third)
                : (second.isAfter(third) ? second : third);
    }

    private record ExtractionDocuments(Document reference, Document invoice) {
    }
}
