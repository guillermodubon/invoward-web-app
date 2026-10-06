package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.exception.AnalysisExtractionNotAllowedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ConfirmedDocumentTypeInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionLockedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionReviewException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReviewUpdate;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Applies a complete user review to both extraction drafts atomically. */
public class UpdateExtractionService {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final AnalysisRepository analysisRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final Clock clock;
    private final int maxLineItems;

    public UpdateExtractionService(
            AnalysisRepository analysisRepository,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            Clock clock,
            int maxLineItems) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
        this.clock = Objects.requireNonNull(clock);
        if (maxLineItems < 1 || maxLineItems > 500) {
            throw new IllegalArgumentException("maxLineItems must be between 1 and 500");
        }
        this.maxLineItems = maxLineItems;
    }

    @Transactional
    public ExtractionReview update(UUID analysisId, AnalysisOwner owner, ExtractionReviewUpdate request) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        if (analysis.status() != AnalysisStatus.AWAITING_CONFIRMATION) {
            throw new AnalysisExtractionNotAllowedException();
        }

        List<Document> documents = orderedDocuments(analysisId);
        ExtractionReviewUpdate.DocumentUpdate referenceRequest = requestFor(
                request, documents.get(0));
        ExtractionReviewUpdate.DocumentUpdate invoiceRequest = requestFor(
                request, documents.get(1));
        ExtractionPair current = loadDraftPair(documents, referenceRequest, invoiceRequest);

        UpdatePlan referencePlan = prepareUpdate(documents.get(0), current.reference(), referenceRequest);
        UpdatePlan invoicePlan = prepareUpdate(documents.get(1), current.invoice(), invoiceRequest);

        Document savedReference = saveDocumentType(
                referencePlan.originalDocument(), referencePlan.document());
        Document savedInvoice = saveDocumentType(
                invoicePlan.originalDocument(), invoicePlan.document());
        PersistedExtraction savedReferenceExtraction = extractedDocumentRepository
                .update(referencePlan.extraction(), now)
                .orElseThrow(ExtractionConflictException::new);
        PersistedExtraction savedInvoiceExtraction = extractedDocumentRepository
                .update(invoicePlan.extraction(), now)
                .orElseThrow(ExtractionConflictException::new);

        return new ExtractionReview(
                reviewed(savedReference, savedReferenceExtraction),
                reviewed(savedInvoice, savedInvoiceExtraction));
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

    private static ExtractionReviewUpdate.DocumentUpdate requestFor(
            ExtractionReviewUpdate request, Document document) {
        return request.documents().stream()
                .filter(candidate -> candidate.documentId().equals(document.id()))
                .findFirst()
                .orElseThrow(ExtractionConflictException::new);
    }

    private ExtractionPair loadDraftPair(
            List<Document> documents,
            ExtractionReviewUpdate.DocumentUpdate referenceRequest,
            ExtractionReviewUpdate.DocumentUpdate invoiceRequest) {
        var reference = extractedDocumentRepository.findByDocumentId(documents.get(0).id());
        var invoice = extractedDocumentRepository.findByDocumentId(documents.get(1).id());
        if (reference.isEmpty() && invoice.isEmpty()) {
            throw new ExtractionNotFoundException();
        }
        if (reference.isEmpty() || invoice.isEmpty()) {
            throw new ExtractionConflictException();
        }
        PersistedExtraction persistedReference = reference.orElseThrow();
        PersistedExtraction persistedInvoice = invoice.orElseThrow();
        if (persistedReference.extraction().status() == ExtractionStatus.CONFIRMED
                || persistedInvoice.extraction().status() == ExtractionStatus.CONFIRMED) {
            throw new ExtractionLockedException();
        }
        if (persistedReference.extraction().status() != ExtractionStatus.DRAFT
                || persistedInvoice.extraction().status() != ExtractionStatus.DRAFT) {
            throw new ExtractionConflictException();
        }
        if (persistedReference.version() != referenceRequest.expectedVersion()
                || persistedInvoice.version() != invoiceRequest.expectedVersion()) {
            throw new ExtractionConflictException();
        }
        return new ExtractionPair(persistedReference, persistedInvoice);
    }

    private UpdatePlan prepareUpdate(
            Document document,
            PersistedExtraction current,
            ExtractionReviewUpdate.DocumentUpdate request) {
        Document updatedDocument;
        try {
            updatedDocument = document.withConfirmedType(request.confirmedType());
        } catch (IllegalArgumentException invalidType) {
            throw new ConfirmedDocumentTypeInvalidException();
        }

        List<ExtractedLineItem> lines = updatedLines(current, request.lines());
        ExtractedDocument updated = draft(current.extraction(), request, lines);
        List<UUID> lineIds = requestedLineIds(current, request.lines());
        return new UpdatePlan(document, updatedDocument, current.withExtraction(updated, lineIds));
    }

    private List<ExtractedLineItem> updatedLines(
            PersistedExtraction current, List<ExtractionReviewUpdate.LineUpdate> updates) {
        if (updates.size() > maxLineItems) {
            throw new InvalidExtractionReviewException();
        }
        Map<UUID, ExtractedLineItem> existing = existingLines(current);
        HashSet<UUID> usedIds = new HashSet<>();
        List<ExtractedLineItem> result = new ArrayList<>(updates.size());
        for (int position = 0; position < updates.size(); position++) {
            ExtractionReviewUpdate.LineUpdate update = updates.get(position);
            if (update.id() != null && !usedIds.add(update.id())) {
                throw new InvalidExtractionReviewException();
            }
            ExtractedLineItem candidate = line(update, position, null, null, null);
            if (update.id() == null) {
                result.add(candidate);
                continue;
            }
            ExtractedLineItem previous = existing.get(update.id());
            if (previous == null) {
                throw new InvalidExtractionReviewException();
            }
            result.add(sameBusinessFields(previous, candidate)
                    ? line(update, position, previous.pageNumber(), previous.sourceText(), previous.boundingBox())
                    : candidate);
        }
        return List.copyOf(result);
    }

    private static Map<UUID, ExtractedLineItem> existingLines(PersistedExtraction current) {
        Map<UUID, ExtractedLineItem> existing = new HashMap<>();
        for (int index = 0; index < current.lineItemIds().size(); index++) {
            existing.put(current.lineItemIds().get(index), current.extraction().lines().get(index));
        }
        return existing;
    }

    private static List<UUID> requestedLineIds(
            PersistedExtraction current, List<ExtractionReviewUpdate.LineUpdate> updates) {
        List<UUID> ids = new ArrayList<>(updates.size());
        HashSet<UUID> generated = new HashSet<>(current.lineItemIds());
        for (ExtractionReviewUpdate.LineUpdate update : updates) {
            if (update.id() != null) {
                ids.add(update.id());
                continue;
            }
            UUID id;
            do {
                id = UUID.randomUUID();
            } while (!generated.add(id));
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static ExtractedLineItem line(
            ExtractionReviewUpdate.LineUpdate update,
            int position,
            Integer pageNumber,
            String sourceText,
            BoundingBox boundingBox) {
        try {
            return new ExtractedLineItem(position, update.itemCode(), update.description(), update.quantity(),
                    update.unit(), update.unitPrice(), update.discountAmount(), update.taxAmount(),
                    update.lineTotal(), pageNumber, sourceText, boundingBox);
        } catch (IllegalArgumentException invalidLine) {
            throw new InvalidExtractionReviewException();
        }
    }

    private static boolean sameBusinessFields(ExtractedLineItem previous, ExtractedLineItem updated) {
        return Objects.equals(previous.itemCode(), updated.itemCode())
                && Objects.equals(previous.description(), updated.description())
                && sameAmount(previous.quantity(), updated.quantity())
                && Objects.equals(previous.unit(), updated.unit())
                && sameAmount(previous.unitPrice(), updated.unitPrice())
                && sameAmount(previous.discountAmount(), updated.discountAmount())
                && sameAmount(previous.taxAmount(), updated.taxAmount())
                && sameAmount(previous.lineTotal(), updated.lineTotal());
    }

    private static boolean sameAmount(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static ExtractedDocument draft(
            ExtractedDocument current,
            ExtractionReviewUpdate.DocumentUpdate request,
            List<ExtractedLineItem> lines) {
        try {
            return ExtractedDocument.draft(current.extractionSource(), request.vendorName(), request.documentNumber(),
                    request.documentDate(), request.currency(), request.subtotal(), request.discountTotal(),
                    request.taxTotal(), request.total(), lines);
        } catch (IllegalArgumentException invalidHeader) {
            throw new InvalidExtractionReviewException();
        }
    }

    private Document saveDocumentType(Document current, Document updated) {
        if (current.confirmedType() == updated.confirmedType()) {
            return current;
        }
        return documentRepository.updateTypes(updated).orElseThrow(ExtractionConflictException::new);
    }

    private static ExtractionReview.ReviewedDocument reviewed(
            Document document, PersistedExtraction extraction) {
        return new ExtractionReview.ReviewedDocument(
                extraction.id(), document.id(), document.role(), document.confirmedType(),
                extraction.version(), extraction.lineItemIds(), extraction.extraction());
    }

    private record UpdatePlan(Document originalDocument, Document document, PersistedExtraction extraction) {
    }

    private record ExtractionPair(PersistedExtraction reference, PersistedExtraction invoice) {
    }
}
