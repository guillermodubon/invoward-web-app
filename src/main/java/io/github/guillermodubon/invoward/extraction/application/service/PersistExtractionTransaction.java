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
import io.github.guillermodubon.invoward.extraction.application.ExtractionVersion;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDocumentPair;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionPreparation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persists both validated extraction drafts and workflow state as one owner-scoped transaction. */
@Service
public class PersistExtractionTransaction {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final Clock clock;

    public PersistExtractionTransaction(
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
    public ExtractionReview persist(
            UUID analysisId,
            AnalysisOwner owner,
            ExtractionPreparation.Ready preparation) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(preparation, "preparation must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        if (analysis.status() != AnalysisStatus.CLASSIFYING) {
            throw new ExtractionConflictException();
        }

        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId)
                .filter(current -> current.status() == AnalysisJobStatus.WAITING_FOR_USER
                        && current.currentStage() == AnalysisStatus.CLASSIFYING)
                .orElseThrow(ExtractionConflictException::new);
        Instant transitionAt = latest(now, analysis.updatedAt(), job.updatedAt());

        ExtractionDocumentPair documents = requireCurrentDocuments(
                analysis, preparation.documents(), transitionAt);
        requireNewDrafts(
                preparation.referenceExtraction(), preparation.invoiceExtraction(), preparation.modelId());

        if (extractedDocumentRepository.findByDocumentId(documents.reference().id()).isPresent()
                || extractedDocumentRepository.findByDocumentId(documents.invoice().id()).isPresent()) {
            throw new ExtractionConflictException();
        }

        PersistedExtraction reference = extractedDocumentRepository.create(
                documents.reference().id(), preparation.referenceExtraction(),
                ExtractionVersion.EXTRACTOR_VERSION, preparation.modelId(),
                ExtractionVersion.SCHEMA_VERSION, transitionAt);
        PersistedExtraction invoice = extractedDocumentRepository.create(
                documents.invoice().id(), preparation.invoiceExtraction(),
                ExtractionVersion.EXTRACTOR_VERSION, preparation.modelId(),
                ExtractionVersion.SCHEMA_VERSION, transitionAt);

        analysisRepository.update(analysis.awaitExtractionConfirmation(transitionAt));
        analysisJobRepository.update(job.waitForExtractionConfirmation(transitionAt));

        return new ExtractionReview(
                reviewed(documents.reference(), reference),
                reviewed(documents.invoice(), invoice));
    }

    private List<Document> orderedDocuments(UUID analysisId) {
        List<Document> documents = documentRepository.findByAnalysisId(analysisId).stream()
                .sorted(ROLE_ORDER)
                .toList();
        if (documents.size() != 2
                || documents.get(0).role() != DocumentRole.REFERENCE
                || documents.get(1).role() != DocumentRole.INVOICE) {
            throw new DocumentsRequiredException();
        }
        return documents;
    }

    private ExtractionDocumentPair requireCurrentDocuments(
            Analysis analysis,
            ExtractionDocumentPair preparedDocuments,
            Instant now) {
        List<Document> current = orderedDocuments(analysis.id());
        Document reference = current.get(0);
        Document invoice = current.get(1);
        if (!matches(reference, preparedDocuments.reference())
                || !matches(invoice, preparedDocuments.invoice())
                || !Objects.equals(reference.expiresAt(), analysis.expiresAt())
                || !Objects.equals(invoice.expiresAt(), analysis.expiresAt())
                || expired(reference, now)
                || expired(invoice, now)) {
            throw new DocumentClassificationConflictException();
        }
        return new ExtractionDocumentPair(reference, invoice);
    }

    private static boolean matches(Document current, Document prepared) {
        return current.id().equals(prepared.id())
                && current.analysisId().equals(prepared.analysisId())
                && current.role() == prepared.role()
                && current.detectedType() != null
                && current.confirmedType() != null
                && current.detectedType() == prepared.detectedType()
                && current.confirmedType() == prepared.confirmedType();
    }

    private static boolean expired(Document document, Instant now) {
        return document.expiresAt() != null && !document.expiresAt().isAfter(now);
    }

    private static void requireNewDrafts(
            ExtractedDocument reference,
            ExtractedDocument invoice,
            String modelId) {
        if (modelId.isBlank() || modelId.length() > 120
                || !isNewDraft(reference) || !isNewDraft(invoice)) {
            throw new ExtractionConflictException();
        }
    }

    private static boolean isNewDraft(ExtractedDocument extraction) {
        return extraction.status() == ExtractionStatus.DRAFT && extraction.confirmedAt() == null;
    }

    private static ExtractionReview.ReviewedDocument reviewed(
            Document document,
            PersistedExtraction extraction) {
        return new ExtractionReview.ReviewedDocument(
                extraction.id(), document.id(), document.role(), document.confirmedType(),
                extraction.version(), extraction.lineItemIds(), extraction.extraction());
    }

    private static Instant latest(Instant first, Instant second, Instant third) {
        return first.isAfter(second)
                ? (first.isAfter(third) ? first : third)
                : (second.isAfter(third) ? second : third);
    }
}
