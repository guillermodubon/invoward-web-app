package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact source locator from a confirmed extraction; it never invents page or source data. */
public record DiscrepancyEvidence(
        UUID id,
        UUID discrepancyId,
        UUID documentId,
        EvidenceSide side,
        int pageNumber,
        String sourceText,
        BoundingBox boundingBox,
        Instant createdAt) {

    public DiscrepancyEvidence {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(discrepancyId, "discrepancyId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        if (sourceText != null && sourceText.isBlank()) {
            sourceText = null;
        }
    }

    @Override
    public String toString() {
        return "DiscrepancyEvidence[id=" + id + ", discrepancyId=" + discrepancyId
                + ", documentId=" + documentId + ", side=" + side + ", pageNumber=" + pageNumber + "]";
    }
}
