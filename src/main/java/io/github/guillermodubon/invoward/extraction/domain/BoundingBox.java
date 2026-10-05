package io.github.guillermodubon.invoward.extraction.domain;

public record BoundingBox(double xMin, double yMin, double xMax, double yMax) {

    public BoundingBox {
        requireNormalizedCoordinate(xMin, "xMin");
        requireNormalizedCoordinate(yMin, "yMin");
        requireNormalizedCoordinate(xMax, "xMax");
        requireNormalizedCoordinate(yMax, "yMax");
        if (xMin >= xMax || yMin >= yMax) {
            throw new IllegalArgumentException("Bounding box minimum coordinates must be less than maximum coordinates");
        }
    }

    private static void requireNormalizedCoordinate(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be finite and between 0 and 1");
        }
    }
}
