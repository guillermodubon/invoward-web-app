package io.github.guillermodubon.invoward.extraction.api.model;

import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;

/** Normalized source-document coordinates safe for review clients. */
public record BoundingBoxResponse(double xMin, double yMin, double xMax, double yMax) {

    public static BoundingBoxResponse from(BoundingBox boundingBox) {
        return boundingBox == null ? null : new BoundingBoxResponse(
                boundingBox.xMin(), boundingBox.yMin(), boundingBox.xMax(), boundingBox.yMax());
    }
}
