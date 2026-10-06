package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.document.domain.DocumentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Full replacement input for the two human-reviewed extraction drafts. */
public record ExtractionReviewUpdate(List<DocumentUpdate> documents) {

    public ExtractionReviewUpdate {
        Objects.requireNonNull(documents, "documents must not be null");
        documents = List.copyOf(documents);
        if (documents.size() != 2
                || new HashSet<>(documents.stream().map(DocumentUpdate::documentId).toList()).size() != 2) {
            throw new IllegalArgumentException("review update must contain two different documents");
        }
    }

    public record DocumentUpdate(
            UUID documentId,
            long expectedVersion,
            DocumentType confirmedType,
            String vendorName,
            String documentNumber,
            LocalDate documentDate,
            String currency,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            List<LineUpdate> lines) {

        public DocumentUpdate {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(confirmedType, "confirmedType must not be null");
            Objects.requireNonNull(lines, "lines must not be null");
            lines = List.copyOf(lines);
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
        }
    }

    public record LineUpdate(
            UUID id,
            String itemCode,
            String description,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            BigDecimal lineTotal) {
    }
}
