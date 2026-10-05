package io.github.guillermodubon.invoward.extraction.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundingBoxTest {

    @Test
    void acceptsOrderedFiniteCoordinatesWithinNormalizedBounds() {
        assertDoesNotThrow(() -> new BoundingBox(0, 0.1, 1, 0.9));
    }

    @Test
    void rejectsNonFiniteOutOfRangeOrInvertedCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(Double.NaN, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0, Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(-0.1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0, 1.1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0.5, 0, 0.5, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0.8, 1, 0.2));
    }
}
