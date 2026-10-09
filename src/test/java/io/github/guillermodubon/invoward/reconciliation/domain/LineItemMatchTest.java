package io.github.guillermodubon.invoward.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LineItemMatchTest {

    private static final Instant CREATED = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant UPDATED = CREATED.plusSeconds(10);
    private static final UUID REFERENCE = UUID.randomUUID();
    private static final UUID INVOICE = UUID.randomUUID();

    @Test
    void acceptsAllDatabaseMatchShapes() {
        assertDoesNotThrow(() -> match(LineMatchStatus.MATCHED, REFERENCE, INVOICE, null));
        assertDoesNotThrow(() -> match(LineMatchStatus.NEEDS_REVIEW, REFERENCE, INVOICE, null));
        assertDoesNotThrow(() -> match(LineMatchStatus.UNMATCHED_REFERENCE, REFERENCE, null, null));
        assertDoesNotThrow(() -> match(LineMatchStatus.UNMATCHED_INVOICE, null, INVOICE, null));
    }

    @Test
    void rejectsLineIdsThatDoNotMatchTheStatusShape() {
        assertThrows(IllegalArgumentException.class,
                () -> match(LineMatchStatus.MATCHED, REFERENCE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> match(LineMatchStatus.UNMATCHED_REFERENCE, REFERENCE, INVOICE, null));
        assertThrows(IllegalArgumentException.class,
                () -> match(LineMatchStatus.UNMATCHED_INVOICE, null, null, null));
    }

    @Test
    void validatesConfidenceRangeAndScale() {
        assertDoesNotThrow(() -> match(LineMatchStatus.MATCHED, REFERENCE, INVOICE, new BigDecimal("0.0000")));
        assertDoesNotThrow(() -> match(LineMatchStatus.MATCHED, REFERENCE, INVOICE, new BigDecimal("1.0000")));
        assertThrows(IllegalArgumentException.class,
                () -> match(LineMatchStatus.MATCHED, REFERENCE, INVOICE, new BigDecimal("-0.0001")));
        assertThrows(IllegalArgumentException.class,
                () -> match(LineMatchStatus.MATCHED, REFERENCE, INVOICE, new BigDecimal("1.0001")));
        assertThrows(IllegalArgumentException.class,
                () -> match(LineMatchStatus.MATCHED, REFERENCE, INVOICE, new BigDecimal("0.12345")));
    }

    @Test
    void validatesVersionAndRequiredTimestamps() {
        assertThrows(IllegalArgumentException.class, () -> new LineItemMatch(
                UUID.randomUUID(), UUID.randomUUID(), REFERENCE, INVOICE,
                LineMatchStatus.MATCHED, LineMatchMethod.FUZZY, new BigDecimal("0.9000"), -1,
                null, CREATED, UPDATED));
        assertThrows(NullPointerException.class, () -> new LineItemMatch(
                UUID.randomUUID(), UUID.randomUUID(), REFERENCE, INVOICE,
                LineMatchStatus.MATCHED, LineMatchMethod.FUZZY, new BigDecimal("0.9000"), 0,
                null, null, UPDATED));
        assertDoesNotThrow(() -> new LineItemMatch(
                UUID.randomUUID(), UUID.randomUUID(), REFERENCE, INVOICE,
                LineMatchStatus.MATCHED, LineMatchMethod.FUZZY, new BigDecimal("0.9000"), 3,
                UPDATED, CREATED, CREATED));
    }

    private static LineItemMatch match(
            LineMatchStatus status, UUID reference, UUID invoice, BigDecimal confidence) {
        return new LineItemMatch(UUID.randomUUID(), UUID.randomUUID(), reference, invoice,
                status, LineMatchMethod.FUZZY, confidence, 0, null, CREATED, UPDATED);
    }
}
