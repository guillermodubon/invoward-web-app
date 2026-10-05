package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;

import java.util.Objects;
import java.util.List;
import java.util.UUID;

/** Review state for the single V1 reference/invoice pair. */
public record ExtractionReview(ReviewedDocument reference, ReviewedDocument invoice) {

    public ExtractionReview {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(invoice, "invoice must not be null");
        if (reference.role() != DocumentRole.REFERENCE || invoice.role() != DocumentRole.INVOICE) {
            throw new IllegalArgumentException("review must contain one reference and one invoice");
        }
        if (reference.documentId().equals(invoice.documentId())) {
            throw new IllegalArgumentException("reference and invoice must be different documents");
        }
    }

    public record ReviewedDocument(
            UUID extractedDocumentId,
            UUID documentId,
            DocumentRole role,
            DocumentType confirmedType,
            long version,
            List<UUID> lineItemIds,
            ExtractedDocument extraction) {

        public ReviewedDocument {
            Objects.requireNonNull(extractedDocumentId, "extractedDocumentId must not be null");
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(confirmedType, "confirmedType must not be null");
            lineItemIds = List.copyOf(lineItemIds);
            Objects.requireNonNull(extraction, "extraction must not be null");
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
            if (lineItemIds.size() != extraction.lines().size()
                    || new java.util.HashSet<>(lineItemIds).size() != lineItemIds.size()) {
                throw new IllegalArgumentException("line item identities must match the review lines");
            }
        }
    }
}
