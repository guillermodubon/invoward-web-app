package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.AnalysisExtractionNotAllowedException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentTypesNotDetectedException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionAlreadyConfirmedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDocumentPair;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionPreparation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authorizes, persists human type choices, then resolves and validates both provider drafts. */
public final class PrepareExtractionService {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final GetAnalysisService analysisService;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final ConfirmExtractionTypesTransaction confirmTypesTransaction;
    private final ExtractionCacheService cacheService;
    private final DocumentIntelligenceFileMaterializer fileMaterializer;
    private final DocumentIntelligence documentIntelligence;
    private final String modelId;
    private final Clock clock;

    public PrepareExtractionService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            ConfirmExtractionTypesTransaction confirmTypesTransaction,
            ExtractionCacheService cacheService,
            DocumentIntelligenceFileMaterializer fileMaterializer,
            DocumentIntelligence documentIntelligence,
            String modelId,
            Clock clock) {
        this.analysisService = Objects.requireNonNull(analysisService);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
        this.confirmTypesTransaction = Objects.requireNonNull(confirmTypesTransaction);
        this.cacheService = Objects.requireNonNull(cacheService);
        this.fileMaterializer = Objects.requireNonNull(fileMaterializer);
        this.documentIntelligence = Objects.requireNonNull(documentIntelligence);
        this.modelId = modelId == null ? "" : modelId.strip();
        this.clock = Objects.requireNonNull(clock);
    }

    public ExtractionPreparation prepare(
            UUID analysisId,
            AnalysisOwner owner,
            ExtractionRequestTypes requestedTypes) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(requestedTypes, "requestedTypes must not be null");

        Analysis analysis = analysisService.get(analysisId, owner);

        List<Document> documents = requiredDocuments(analysisId);
        Instant now = clock.instant();
        Optional<PersistedExtraction> referenceExisting = extractedDocumentRepository
                .findByDocumentId(documents.get(0).id());
        Optional<PersistedExtraction> invoiceExisting = extractedDocumentRepository
                .findByDocumentId(documents.get(1).id());
        if (referenceExisting.isPresent() || invoiceExisting.isPresent()) {
            return existingDraftOrConflict(documents.get(0), referenceExisting,
                    documents.get(1), invoiceExisting);
        }

        if (analysis.status() != AnalysisStatus.CLASSIFYING) {
            throw new AnalysisExtractionNotAllowedException();
        }
        requireActive(documents, now);
        if (documents.stream().anyMatch(document -> document.detectedType() == null)) {
            throw new DocumentTypesNotDetectedException();
        }

        ExtractionDocumentPair confirmed = confirmTypesTransaction.confirm(analysisId, owner, requestedTypes);
        if (modelId.isBlank() || modelId.length() > 120) {
            throw new DocumentIntelligenceException(DocumentIntelligenceException.Failure.UNAVAILABLE);
        }

        ExtractedDocument reference = extract(confirmed.reference());
        ExtractedDocument invoice = extract(confirmed.invoice());
        return new ExtractionPreparation.Ready(confirmed, reference, invoice, modelId);
    }

    private ExtractedDocument extract(Document document) {
        Optional<ExtractedDocument> cached = cacheService.findValid(
                document.sha256(), modelId, document.pageCount());
        if (cached.isPresent()) {
            return cached.orElseThrow();
        }
        DocumentType confirmedType = Objects.requireNonNull(document.confirmedType());
        return fileMaterializer.withMaterializedFile(document, input -> {
            var providerOutput = documentIntelligence.extract(input, confirmedType);
            return cacheService.cacheProviderOutput(
                    document.sha256(), modelId, providerOutput, document.pageCount());
        });
    }

    private ExtractionPreparation existingDraftOrConflict(
            Document reference,
            Optional<PersistedExtraction> referenceExtraction,
            Document invoice,
            Optional<PersistedExtraction> invoiceExtraction) {
        if (referenceExtraction.isEmpty() || invoiceExtraction.isEmpty()) {
            if (referenceExtraction.map(PersistedExtraction::extraction)
                    .filter(extraction -> extraction.status() == ExtractionStatus.CONFIRMED).isPresent()
                    || invoiceExtraction.map(PersistedExtraction::extraction)
                    .filter(extraction -> extraction.status() == ExtractionStatus.CONFIRMED).isPresent()) {
                throw new ExtractionAlreadyConfirmedException();
            }
            throw new ExtractionConflictException();
        }

        PersistedExtraction persistedReference = referenceExtraction.orElseThrow();
        PersistedExtraction persistedInvoice = invoiceExtraction.orElseThrow();
        if (persistedReference.extraction().status() == ExtractionStatus.CONFIRMED
                || persistedInvoice.extraction().status() == ExtractionStatus.CONFIRMED) {
            throw new ExtractionAlreadyConfirmedException();
        }
        if (persistedReference.extraction().status() != ExtractionStatus.DRAFT
                || persistedInvoice.extraction().status() != ExtractionStatus.DRAFT
                || reference.confirmedType() == null || invoice.confirmedType() == null) {
            throw new ExtractionConflictException();
        }
        return new ExtractionPreparation.ExistingDraft(new ExtractionReview(
                reviewed(reference, persistedReference), reviewed(invoice, persistedInvoice)));
    }

    private static ExtractionReview.ReviewedDocument reviewed(
            Document document,
            PersistedExtraction extraction) {
        return new ExtractionReview.ReviewedDocument(
                extraction.id(), document.id(), document.role(), document.confirmedType(),
                extraction.version(), extraction.lineItemIds(), extraction.extraction());
    }

    private List<Document> requiredDocuments(UUID analysisId) {
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

    private static void requireActive(List<Document> documents, Instant now) {
        if (documents.stream().anyMatch(document -> document.expiresAt() != null
                && !document.expiresAt().isAfter(now))) {
            throw new DocumentClassificationConflictException();
        }
    }
}
