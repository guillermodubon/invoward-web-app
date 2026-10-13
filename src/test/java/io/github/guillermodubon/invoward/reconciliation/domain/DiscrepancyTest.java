package io.github.guillermodubon.invoward.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscrepancyTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID ID = UUID.randomUUID();
    private static final UUID ANALYSIS_ID = UUID.randomUUID();

    @Test
    void newDiscrepancyUsesOpenStateWithoutResolutionMetadata() {
        Discrepancy open = open();
        assertEquals(DiscrepancyResolutionStatus.OPEN, open.resolutionStatus());
        assertEquals(0, open.version());
        assertEquals(null, open.resolutionReason());
        assertEquals(null, open.resolutionNote());
        assertEquals(null, open.resolvedAt());
    }

    @Test
    void supportsEveryApprovedResolutionTransitionAndReopeningClearsDetails() {
        Instant later = CREATED.plusSeconds(5);
        Discrepancy accepted = open().transitionResolution(
                DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionReason.APPROVED_CHANGE, null, later);
        assertEquals(later, accepted.resolvedAt());

        Discrepancy resolved = accepted.transitionResolution(
                DiscrepancyResolutionStatus.RESOLVED,
                DiscrepancyResolutionReason.SUPPLIER_CORRECTION_EXPECTED,
                " correction received ", later.plusSeconds(1));
        assertEquals("correction received", resolved.resolutionNote());
        assertEquals(later.plusSeconds(1), resolved.resolvedAt());

        Discrepancy acceptedAgain = resolved.transitionResolution(
                DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionReason.OTHER,
                "approved after review", later.plusSeconds(2));
        assertEquals(DiscrepancyResolutionStatus.ACCEPTED, acceptedAgain.resolutionStatus());

        Discrepancy reopenedFromAccepted = acceptedAgain.transitionResolution(
                DiscrepancyResolutionStatus.OPEN, DiscrepancyResolutionReason.OTHER,
                "ignored because OPEN clears details", later.plusSeconds(3));
        assertEquals(DiscrepancyResolutionStatus.OPEN, reopenedFromAccepted.resolutionStatus());
        assertEquals(null, reopenedFromAccepted.resolutionReason());
        assertEquals(null, reopenedFromAccepted.resolutionNote());
        assertEquals(null, reopenedFromAccepted.resolvedAt());

        Discrepancy reopenedFromResolved = resolved.transitionResolution(
                DiscrepancyResolutionStatus.OPEN, null, null, later.plusSeconds(2));
        assertEquals(DiscrepancyResolutionStatus.OPEN, reopenedFromResolved.resolutionStatus());
    }

    @Test
    void requiresReasonAndOtherNoteForClosedStates() {
        Instant resolvedAt = CREATED.plusSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new Discrepancy(
                ID, ANALYSIS_ID, null, DiscrepancyType.TOTAL_MISMATCH, "100", "110",
                new BigDecimal("10.0000"), null, "Total differs", DiscrepancyResolutionStatus.ACCEPTED,
                null, null, 0, resolvedAt, CREATED, resolvedAt));
        assertThrows(IllegalArgumentException.class, () -> open().transitionResolution(
                DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionReason.OTHER,
                "  ", resolvedAt));
        assertThrows(IllegalArgumentException.class, () -> open().transitionResolution(
                DiscrepancyResolutionStatus.ACCEPTED, null, null, resolvedAt));
    }

    @Test
    void normalizesNoteWhitespaceAndEnforcesUnicodeCodePointLimit() {
        String note = "x".repeat(1999) + "😀";
        Discrepancy resolved = open().transitionResolution(
                DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionReason.OTHER,
                "  " + note + "  ", CREATED.plusSeconds(1));
        assertEquals(note, resolved.resolutionNote());
        assertThrows(IllegalArgumentException.class, () -> open().transitionResolution(
                DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionReason.OTHER,
                "x".repeat(2001), CREATED.plusSeconds(1)));
    }

    @Test
    void rejectsControlCharactersEvenAtTheEdgesOfANote() {
        assertThrows(IllegalArgumentException.class, () -> open().transitionResolution(
                DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionReason.OTHER,
                "\nvalid note", CREATED.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> open().transitionResolution(
                DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionReason.OTHER,
                "valid\u0000note", CREATED.plusSeconds(1)));
    }

    @Test
    void rejectsUnapprovedTransitionsAndInvalidTimes() {
        assertThrows(IllegalStateException.class, () -> open().transitionResolution(
                DiscrepancyResolutionStatus.OPEN, null, null, CREATED.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> open().transitionResolution(
                DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionReason.APPROVED_CHANGE,
                null, CREATED.minusSeconds(1)));
    }

    @Test
    void validatesNumericPrecisionValuesAndVersion() {
        assertThrows(IllegalArgumentException.class, () -> discrepancy(new BigDecimal("-0.01"), null, 0));
        assertThrows(IllegalArgumentException.class, () -> discrepancy(new BigDecimal("1.00001"), null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> discrepancy(null, new BigDecimal("0.0000001"), 0));
        assertThrows(IllegalArgumentException.class, () -> discrepancy(null, null, -1));
    }

    @Test
    void validatesRequiredTextAndDatabaseValueLength() {
        assertThrows(IllegalArgumentException.class, () -> new Discrepancy(
                ID, ANALYSIS_ID, null, DiscrepancyType.TOTAL_MISMATCH, null, null, null, null,
                "  ", DiscrepancyResolutionStatus.OPEN, null, null, 0, null, CREATED, CREATED));
        assertThrows(IllegalArgumentException.class, () -> new Discrepancy(
                ID, ANALYSIS_ID, null, DiscrepancyType.TOTAL_MISMATCH, "😀".repeat(256), null, null, null,
                "Total differs", DiscrepancyResolutionStatus.OPEN, null, null, 0, null, CREATED, CREATED));
    }

    @Test
    void safeStringRepresentationOmitsUserTextAndFinancialValues() {
        Discrepancy discrepancy = new Discrepancy(ID, ANALYSIS_ID, null, DiscrepancyType.TOTAL_MISMATCH,
                "100.00", "110.00", new BigDecimal("10.0000"), null, "sensitive explanation",
                DiscrepancyResolutionStatus.OPEN, null, null, 0, null, CREATED, CREATED);
        String rendered = discrepancy.toString();
        assertTrue(!rendered.contains("sensitive explanation"));
        assertTrue(!rendered.contains("100.00"));
        assertTrue(!rendered.contains("110.00"));
    }

    private static Discrepancy open() {
        return discrepancy(new BigDecimal("10.0000"), new BigDecimal("10.000000"), 0);
    }

    private static Discrepancy discrepancy(BigDecimal absolute, BigDecimal percentage, long version) {
        return new Discrepancy(ID, ANALYSIS_ID, null, DiscrepancyType.TOTAL_MISMATCH,
                "100.0000", "110.0000", absolute, percentage, "The document totals differ.",
                DiscrepancyResolutionStatus.OPEN, null, null, version, null, CREATED, CREATED);
    }
}
