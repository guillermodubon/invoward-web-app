package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscrepancyEvidenceTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void retainsOnlyTheProvidedEvidenceAndRequiresPositivePage() {
        BoundingBox box = new BoundingBox(0.1, 0.2, 0.8, 0.9);
        DiscrepancyEvidence evidence = evidence(1, "Printed source", box);
        assertEquals("Printed source", evidence.sourceText());
        assertEquals(box, evidence.boundingBox());
        assertEquals(1, evidence.pageNumber());
        assertNull(evidence(4, "  ", null).sourceText());
    }

    @Test
    void rejectsUnknownOrNonPositivePageRatherThanManufacturingEvidence() {
        assertThrows(IllegalArgumentException.class, () -> evidence(0, "source", null));
        assertThrows(IllegalArgumentException.class, () -> evidence(-1, "source", null));
    }

    @Test
    void safeStringRepresentationOmitsSourceTextAndCoordinates() {
        String rendered = evidence(2, "private invoice passage", new BoundingBox(0.1, 0.2, 0.8, 0.9))
                .toString();
        assertTrue(!rendered.contains("private invoice passage"));
        assertTrue(!rendered.contains("0.1"));
    }

    private static DiscrepancyEvidence evidence(int page, String source, BoundingBox box) {
        return new DiscrepancyEvidence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                EvidenceSide.REFERENCE, page, source, box, CREATED);
    }
}
