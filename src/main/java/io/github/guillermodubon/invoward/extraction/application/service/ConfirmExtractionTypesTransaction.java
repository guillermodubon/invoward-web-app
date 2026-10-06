package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.exception.DocumentNotFoundException;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.ConfirmedDocumentTypeInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentTypesNotDetectedException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDocumentPair;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Atomically saves human-confirmed types after an owner-scoped Analysis lock and state recheck. */
@Service
public class ConfirmExtractionTypesTransaction {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final AnalysisRepository analysisRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final Clock clock;

    public ConfirmExtractionTypesTransaction(
            AnalysisRepository analysisRepository,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public ExtractionDocumentPair confirm(
            UUID analysisId,
            AnalysisOwner owner,
            ExtractionRequestTypes requestedTypes) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(requestedTypes, "requestedTypes must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        if (analysis.status() != AnalysisStatus.CLASSIFYING) {
            throw new DocumentClassificationConflictException();
        }

        List<Document> documents = orderedRequiredDocuments(analysisId);
        requireDetectedTypes(documents);
        requireActive(documents, now);
        if (documents.stream().anyMatch(document -> extractedDocumentRepository
                .findByDocumentId(document.id()).isPresent())) {
            throw new ExtractionConflictException();
        }

        Document reference;
        Document invoice;
        try {
            reference = documents.get(0).withConfirmedType(requestedTypes.referenceType());
            invoice = documents.get(1).withConfirmedType(requestedTypes.invoiceType());
        } catch (IllegalArgumentException invalidType) {
            throw new ConfirmedDocumentTypeInvalidException();
        }

        Document savedReference = documentRepository.updateTypes(reference)
                .orElseThrow(DocumentNotFoundException::new);
        Document savedInvoice = documentRepository.updateTypes(invoice)
                .orElseThrow(DocumentNotFoundException::new);
        return new ExtractionDocumentPair(savedReference, savedInvoice);
    }

    private List<Document> orderedRequiredDocuments(UUID analysisId) {
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

    private static void requireDetectedTypes(List<Document> documents) {
        if (documents.stream().anyMatch(document -> document.detectedType() == null)) {
            throw new DocumentTypesNotDetectedException();
        }
    }

    private static void requireActive(List<Document> documents, Instant now) {
        if (documents.stream().anyMatch(document -> document.expiresAt() != null
                && !document.expiresAt().isAfter(now))) {
            throw new DocumentClassificationConflictException();
        }
    }
}
