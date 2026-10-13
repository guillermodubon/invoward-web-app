package io.github.guillermodubon.invoward.reconciliation.api.model;

import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.domain.EvidenceSide;

import java.util.UUID;

/** Safe evidence projection; storage, ownership, and persistence metadata are intentionally omitted. */
public record DiscrepancyEvidenceResponse(
        UUID id,
        UUID documentId,
        EvidenceSide side,
        int pageNumber,
        String sourceText,
        BoundingBoxResponse boundingBox) {

    public static DiscrepancyEvidenceResponse from(DiscrepancyEvidence evidence) {
        var box = evidence.boundingBox();
        return new DiscrepancyEvidenceResponse(
                evidence.id(), evidence.documentId(), evidence.side(), evidence.pageNumber(), evidence.sourceText(),
                box == null ? null : new BoundingBoxResponse(box.xMin(), box.yMin(), box.xMax(), box.yMax()));
    }

    public record BoundingBoxResponse(double xMin, double yMin, double xMax, double yMax) {
    }
}
