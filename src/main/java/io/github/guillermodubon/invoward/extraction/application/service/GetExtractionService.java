package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads the complete extraction review only after the parent Analysis ownership is established. */
public final class GetExtractionService {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final GetAnalysisService analysisService;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;

    public GetExtractionService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository) {
        this.analysisService = Objects.requireNonNull(analysisService);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
    }

    public ExtractionReview get(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        analysisService.get(analysisId, owner);
        List<Document> documents = documentRepository.findByAnalysisId(analysisId).stream()
                .sorted(ROLE_ORDER)
                .toList();
        if (!isReferenceInvoicePair(documents)) {
            throw new ExtractionNotFoundException();
        }

        Document reference = documents.get(0);
        Document invoice = documents.get(1);
        Optional<PersistedExtraction> referenceExtraction = extractedDocumentRepository
                .findByDocumentId(reference.id());
        Optional<PersistedExtraction> invoiceExtraction = extractedDocumentRepository
                .findByDocumentId(invoice.id());

        if (referenceExtraction.isEmpty() && invoiceExtraction.isEmpty()) {
            throw new ExtractionNotFoundException();
        }
        if (referenceExtraction.isEmpty() || invoiceExtraction.isEmpty()) {
            throw new ExtractionConflictException();
        }

        PersistedExtraction persistedReference = referenceExtraction.orElseThrow();
        PersistedExtraction persistedInvoice = invoiceExtraction.orElseThrow();
        if (reference.confirmedType() == null
                || invoice.confirmedType() == null
                || persistedReference.extraction().status() != persistedInvoice.extraction().status()) {
            throw new ExtractionConflictException();
        }

        return new ExtractionReview(
                reviewed(reference, persistedReference),
                reviewed(invoice, persistedInvoice));
    }

    private static boolean isReferenceInvoicePair(List<Document> documents) {
        return documents.size() == 2
                && documents.get(0).role() == DocumentRole.REFERENCE
                && documents.get(1).role() == DocumentRole.INVOICE;
    }

    private static ExtractionReview.ReviewedDocument reviewed(
            Document document,
            PersistedExtraction extraction) {
        ExtractionStatus status = extraction.extraction().status();
        if (status != ExtractionStatus.DRAFT && status != ExtractionStatus.CONFIRMED) {
            throw new ExtractionConflictException();
        }
        return new ExtractionReview.ReviewedDocument(
                extraction.id(), document.id(), document.role(), document.confirmedType(),
                extraction.version(), extraction.lineItemIds(), extraction.extraction());
    }
}
