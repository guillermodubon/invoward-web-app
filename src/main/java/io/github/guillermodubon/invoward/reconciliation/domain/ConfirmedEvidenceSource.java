package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Confirmed source-document data with persisted line identities in extraction order. */
public record ConfirmedEvidenceSource(
        Document document,
        UUID extractionDocumentId,
        ExtractedDocument extraction,
        List<UUID> lineItemIds) {

    public ConfirmedEvidenceSource {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(extractionDocumentId, "extractionDocumentId must not be null");
        Objects.requireNonNull(extraction, "extraction must not be null");
        lineItemIds = List.copyOf(lineItemIds);

        if (!document.id().equals(extractionDocumentId)) {
            throw new IllegalArgumentException("extraction must belong to its source document");
        }
        if (extraction.status() != ExtractionStatus.CONFIRMED || extraction.confirmedAt() == null) {
            throw new IllegalArgumentException("evidence requires a confirmed extraction");
        }
        if (lineItemIds.size() != extraction.lines().size()
                || new HashSet<>(lineItemIds).size() != lineItemIds.size()) {
            throw new IllegalArgumentException("line identities must match confirmed extraction lines");
        }
        if (lineItemIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("line identities must not contain null");
        }
    }

    ExtractedLineItem line(UUID lineItemId) {
        for (int index = 0; index < lineItemIds.size(); index++) {
            if (lineItemIds.get(index).equals(lineItemId)) {
                return extraction.lines().get(index);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "ConfirmedEvidenceSource[documentId=" + document.id() + ", role=" + document.role()
                + ", lineCount=" + lineItemIds.size() + "]";
    }
}
