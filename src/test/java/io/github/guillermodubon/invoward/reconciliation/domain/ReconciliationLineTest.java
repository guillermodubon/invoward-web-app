package io.github.guillermodubon.invoward.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationLineTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID MATCH_ID = UUID.randomUUID();

    @Test
    void acceptsMatchedAndDifferentRowsWithBothSides() {
        assertDoesNotThrow(() -> line(ReconciliationLineStatus.MATCHED, "Reference", "Invoice"));
        assertDoesNotThrow(() -> line(ReconciliationLineStatus.DIFFERENT, "Reference", "Invoice"));
    }

    @Test
    void acceptsOnlyReferenceOrInvoiceDataForUnmatchedRows() {
        assertDoesNotThrow(() -> missing());
        assertDoesNotThrow(() -> unexpected());
    }

    @Test
    void rejectsMissingOrUnexpectedRowsWithOppositeSideDataOrArithmetic() {
        assertThrows(IllegalArgumentException.class, () -> missingWithInvoiceDescription());
        assertThrows(IllegalArgumentException.class, () -> unexpectedWithReferenceQuantity());
        assertThrows(IllegalArgumentException.class, () -> missingWithDifference());
    }

    @Test
    void requiresDescriptionsForPresentSides() {
        assertThrows(IllegalArgumentException.class,
                () -> line(ReconciliationLineStatus.MATCHED, null, "Invoice"));
        assertThrows(IllegalArgumentException.class,
                () -> line(ReconciliationLineStatus.DIFFERENT, "Reference", " "));
    }

    @Test
    void validatesSnapshotAmountsAndExactNumericCompatibility() {
        assertThrows(IllegalArgumentException.class, () -> lineWithReferencePrice(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> lineWithReferencePrice(new BigDecimal("1.00001")));
        assertThrows(IllegalArgumentException.class,
                () -> lineWithReferencePrice(new BigDecimal("1234567890123456.0000")));
        assertDoesNotThrow(() -> lineWithReferencePrice(new BigDecimal("999999999999999.9999")));
        assertThrows(IllegalArgumentException.class,
                () -> lineWithReferencePrice(new BigDecimal("1000000000000000.0000")));
        assertDoesNotThrow(() -> lineWithReferencePrice(new BigDecimal("1.23000")));
    }

    @Test
    void permitsSignedDifferencesButRequiresDatabaseNumericPrecision() {
        assertDoesNotThrow(() -> lineWithDifferences(
                new BigDecimal("-1.2500"), new BigDecimal("2.0000"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> lineWithDifferences(new BigDecimal("0.00001"), null, null));
    }

    @Test
    void rejectsOverlongDatabaseItemCodesAndRequiresIdentityAndTimestamp() {
        assertThrows(IllegalArgumentException.class,
                () -> lineWithReferenceCode("x".repeat(121)));
        assertThrows(NullPointerException.class,
                () -> new ReconciliationLine(null, ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.MISSING,
                        null, null, "Reference", null, null, null, null, null, null, null,
                        null, null, null, CREATED));
    }

    @Test
    void stringRepresentationDoesNotIncludeDocumentDescriptionsOrAmounts() {
        String rendered = line(ReconciliationLineStatus.DIFFERENT, "private reference description", "private invoice")
                .toString();
        assertTrue(!rendered.contains("private reference description"));
        assertTrue(!rendered.contains("private invoice"));
        assertTrue(!rendered.contains("12.5000"));
    }

    private static ReconciliationLine line(
            ReconciliationLineStatus status, String referenceDescription, String invoiceDescription) {
        return new ReconciliationLine(UUID.randomUUID(), ANALYSIS_ID, MATCH_ID, status,
                "REF-1", "INV-1", referenceDescription, invoiceDescription,
                new BigDecimal("2.0000"), new BigDecimal("2.0000"),
                new BigDecimal("12.5000"), new BigDecimal("12.5000"),
                new BigDecimal("25.0000"), new BigDecimal("25.0000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, CREATED);
    }

    private static ReconciliationLine missing() {
        return new ReconciliationLine(UUID.randomUUID(), ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.MISSING,
                "REF-1", null, "Reference", null, new BigDecimal("2.0000"), null,
                new BigDecimal("12.5000"), null, new BigDecimal("25.0000"), null,
                null, null, null, CREATED);
    }

    private static ReconciliationLine unexpected() {
        return new ReconciliationLine(UUID.randomUUID(), ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.UNEXPECTED,
                null, "INV-1", null, "Invoice", null, new BigDecimal("2.0000"),
                null, new BigDecimal("12.5000"), null, new BigDecimal("25.0000"),
                null, null, null, CREATED);
    }

    private static ReconciliationLine missingWithInvoiceDescription() {
        return new ReconciliationLine(UUID.randomUUID(), ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.MISSING,
                "REF-1", null, "Reference", "unexpected", new BigDecimal("2"), null,
                null, null, null, null, null, null, null, CREATED);
    }

    private static ReconciliationLine unexpectedWithReferenceQuantity() {
        return new ReconciliationLine(UUID.randomUUID(), ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.UNEXPECTED,
                null, "INV-1", null, "Invoice", BigDecimal.ONE, BigDecimal.ONE,
                null, null, null, null, null, null, null, CREATED);
    }

    private static ReconciliationLine missingWithDifference() {
        return new ReconciliationLine(UUID.randomUUID(), ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.MISSING,
                "REF-1", null, "Reference", null, BigDecimal.ONE, null,
                null, null, null, null, BigDecimal.ONE, null, null, CREATED);
    }

    private static ReconciliationLine lineWithReferencePrice(BigDecimal price) {
        return new ReconciliationLine(UUID.randomUUID(), ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.MATCHED,
                null, null, "Reference", "Invoice", null, null, price, null,
                null, null, null, null, null, CREATED);
    }

    private static ReconciliationLine lineWithDifferences(
            BigDecimal quantityDifference, BigDecimal unitPriceDifference, BigDecimal lineTotalDifference) {
        return new ReconciliationLine(UUID.randomUUID(), ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.DIFFERENT,
                null, null, "Reference", "Invoice", null, null, null, null, null, null,
                quantityDifference, unitPriceDifference, lineTotalDifference, CREATED);
    }

    private static ReconciliationLine lineWithReferenceCode(String itemCode) {
        return new ReconciliationLine(UUID.randomUUID(), ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.MATCHED,
                itemCode, null, "Reference", "Invoice", null, null, null, null,
                null, null, null, null, null, CREATED);
    }
}
