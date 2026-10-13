package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationEngineTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final PriceTolerance EXACT = PriceTolerance.exactMatch();

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    void equalMatchedLinesProduceMatchedSnapshotAndNoDiscrepancies() {
        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "2", "10", "1", "2", "20"),
                line("SKU-1", "Widget", "2.0", "10.00", "1.0000", "2", "20.0"),
                EXACT, true);

        assertEquals(1, result.lines().size());
        ReconciliationLine snapshot = result.lines().getFirst();
        assertEquals(ReconciliationLineStatus.MATCHED, snapshot.status());
        assertEquals(0, snapshot.quantityDifference().signum());
        assertEquals(0, snapshot.unitPriceDifference().signum());
        assertEquals(0, snapshot.lineTotalDifference().signum());
        assertTrue(result.discrepancies().isEmpty());
    }

    @Test
    void preservesBigDecimalPrecisionAtTheNumeric194UpperBoundary() {
        BigDecimal maximum = new BigDecimal("999999999999999.9999");
        BigDecimal oneQuantumBelow = new BigDecimal("999999999999999.9998");

        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "1", maximum.toPlainString(), null, null, maximum.toPlainString()),
                line("SKU-1", "Widget", "1", oneQuantumBelow.toPlainString(), null, null,
                        oneQuantumBelow.toPlainString()),
                EXACT, true);

        ReconciliationLine snapshot = result.lines().getFirst();
        assertEquals(0, new BigDecimal("-0.0001").compareTo(snapshot.unitPriceDifference()));
        assertEquals(0, new BigDecimal("-0.0001").compareTo(snapshot.lineTotalDifference()));
        Discrepancy unitPrice = onlyDiscrepancy(result, DiscrepancyType.UNIT_PRICE_MISMATCH);
        assertEquals(0, new BigDecimal("0.0001").compareTo(unitPrice.absoluteDifference()));
    }

    @Test
    void snapshotsUseSignedInvoiceMinusReferenceDeltasWhileDiscrepanciesUseAbsoluteAmounts() {
        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "4", "12", "2", "1", "48"),
                line("SKU-1", "Widget", "3", "10", "1", "0.5", "30"),
                EXACT, true);

        ReconciliationLine snapshot = result.lines().getFirst();
        assertDecimalEquals("-1", snapshot.quantityDifference());
        assertDecimalEquals("-2", snapshot.unitPriceDifference());
        assertDecimalEquals("-18", snapshot.lineTotalDifference());
        assertEquals(List.of(
                        DiscrepancyType.QUANTITY_MISMATCH,
                        DiscrepancyType.UNIT_PRICE_MISMATCH,
                        DiscrepancyType.DISCOUNT_MISMATCH,
                        DiscrepancyType.TAX_MISMATCH),
                result.discrepancies().stream().map(Discrepancy::type).toList());
        assertEquals(List.of("1", "2", "1", "0.5"), result.discrepancies().stream()
                .map(Discrepancy::absoluteDifference)
                .map(BigDecimal::stripTrailingZeros)
                .map(BigDecimal::toPlainString)
                .toList());
    }

    @Test
    void quantityMismatchIsNeverHiddenByMonetaryTolerance() {
        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "2", "10", null, null, "20"),
                line("SKU-1", "Widget", "3", "10", null, null, "20"),
                new PriceTolerance(new BigDecimal("5"), new BigDecimal("2")), true);

        assertEquals(ReconciliationLineStatus.DIFFERENT, result.lines().getFirst().status());
        assertDecimalEquals("1", result.lines().getFirst().quantityDifference());
        Discrepancy discrepancy = onlyDiscrepancy(result, DiscrepancyType.QUANTITY_MISMATCH);
        assertEquals("2", discrepancy.referenceValue());
        assertEquals("3", discrepancy.invoiceValue());
        assertDecimalEquals("1", discrepancy.absoluteDifference());
        assertEquals(new BigDecimal("50.000000"), discrepancy.percentageDifference());
    }

    @Test
    void monetaryToleranceAcceptsTheBoundaryAndRejectsTheNextAmount() {
        PriceTolerance onePercent = new PriceTolerance(BigDecimal.ONE, null);
        LineReconciliationResult boundary = reconcile(
                line("SKU-1", "Widget", "1", "100", null, null, "100"),
                line("SKU-1", "Widget", "1", "101", null, null, "100"), onePercent, true);
        LineReconciliationResult outside = reconcile(
                line("SKU-1", "Widget", "1", "100", null, null, "100"),
                line("SKU-1", "Widget", "1", "101.0001", null, null, "100"), onePercent, true);

        assertTrue(boundary.discrepancies().isEmpty());
        assertEquals(0, boundary.lines().getFirst().unitPriceDifference().compareTo(BigDecimal.ONE));
        assertEquals(DiscrepancyType.UNIT_PRICE_MISMATCH, outside.discrepancies().getFirst().type());
        assertEquals(new BigDecimal("1.0001"), outside.discrepancies().getFirst().absoluteDifference());
    }

    @Test
    void discountAndTaxAreComparedIndependentlyWithTheApprovedTolerance() {
        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "1", "10", "1", "1", "10"),
                line("SKU-1", "Widget", "1", "10", "2", "3", "10"), EXACT, true);

        assertEquals(List.of(DiscrepancyType.DISCOUNT_MISMATCH, DiscrepancyType.TAX_MISMATCH),
                result.discrepancies().stream().map(Discrepancy::type).toList());
        assertEquals("1", result.discrepancies().get(0).referenceValue());
        assertEquals("2", result.discrepancies().get(0).invoiceValue());
        assertEquals("1", result.discrepancies().get(1).referenceValue());
        assertEquals("3", result.discrepancies().get(1).invoiceValue());
        assertTrue(result.discrepancies().stream().allMatch(item -> item.reconciliationLineId() != null));
    }

    @Test
    void unexplainedLineTotalDifferenceCreatesOneTotalDiscrepancy() {
        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "1", "10", null, null, "10"),
                line("SKU-1", "Widget", "1", "10", null, null, "12"), EXACT, true);

        Discrepancy discrepancy = onlyDiscrepancy(result, DiscrepancyType.TOTAL_MISMATCH);
        assertEquals("10", discrepancy.referenceValue());
        assertEquals("12", discrepancy.invoiceValue());
        assertDecimalEquals("2", discrepancy.absoluteDifference());
        assertEquals(new BigDecimal("20.000000"), discrepancy.percentageDifference());
        assertDecimalEquals("2", result.lines().getFirst().lineTotalDifference());
    }

    @Test
    void lineTotalDifferenceExplainedByUnitPriceDoesNotCreateRedundantTotalDiscrepancy() {
        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "1", "10", null, null, "10"),
                line("SKU-1", "Widget", "1", "12", null, null, "12"), EXACT, true);

        assertEquals(List.of(DiscrepancyType.UNIT_PRICE_MISMATCH),
                result.discrepancies().stream().map(Discrepancy::type).toList());
    }

    @Test
    void multipleMeaningfulDifferencesArePreservedWithoutRedundantTotal() {
        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "2", "10", "1", "1", "20"),
                line("SKU-1", "Widget", "3", "12", "2", "3", "24"), EXACT, true);

        assertEquals(List.of(
                        DiscrepancyType.QUANTITY_MISMATCH,
                        DiscrepancyType.UNIT_PRICE_MISMATCH,
                        DiscrepancyType.DISCOUNT_MISMATCH,
                        DiscrepancyType.TAX_MISMATCH),
                result.discrepancies().stream().map(Discrepancy::type).toList());
        assertTrue(result.discrepancies().stream().allMatch(item -> item.resolutionStatus() == DiscrepancyResolutionStatus.OPEN
                && item.version() == 0 && item.resolutionReason() == null && item.resolutionNote() == null
                && item.resolvedAt() == null));
    }

    @Test
    void incompatibleCurrenciesSuppressAllMonetaryDifferencesButKeepQuantityComparison() {
        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "1", "10", "1", "1", "10"),
                line("SKU-1", "Widget", "2", "20", "3", "4", "30"), EXACT, false);

        ReconciliationLine snapshot = result.lines().getFirst();
        assertDecimalEquals("1", snapshot.quantityDifference());
        assertNull(snapshot.unitPriceDifference());
        assertNull(snapshot.lineTotalDifference());
        assertEquals(List.of(DiscrepancyType.QUANTITY_MISMATCH),
                result.discrepancies().stream().map(Discrepancy::type).toList());
    }

    @Test
    void nullMoneyIsNotCoercedToZeroAndBothNullAreEqual() {
        LineReconciliationResult bothNull = reconcile(
                line("SKU-1", "Widget", "1", null, null, null, null),
                line("SKU-1", "Widget", "1", null, null, null, null), EXACT, true);
        LineReconciliationResult oneNull = reconcile(
                line("SKU-1", "Widget", "1", null, null, null, null),
                line("SKU-1", "Widget", "1", "0", null, null, null), EXACT, true);

        assertTrue(bothNull.discrepancies().isEmpty());
        Discrepancy mismatch = onlyDiscrepancy(oneNull, DiscrepancyType.UNIT_PRICE_MISMATCH);
        assertNull(mismatch.referenceValue());
        assertEquals("0", mismatch.invoiceValue());
        assertNull(mismatch.absoluteDifference());
        assertNull(mismatch.percentageDifference());
    }

    @Test
    void referenceOnlyLineProducesMissingRowAndIdentifierWithoutArithmetic() {
        UUID referenceId = UUID.randomUUID();
        LineItemMatch match = match(LineMatchStatus.UNMATCHED_REFERENCE, referenceId, null);

        LineReconciliationResult result = engine.reconcile(
                ANALYSIS_ID, List.of(match), Map.of(referenceId,
                        line("REF-77", "Reference item", "3", "25", "2", "1", "75")),
                Map.of(), EXACT, true, CREATED);

        ReconciliationLine row = result.lines().getFirst();
        assertEquals(ReconciliationLineStatus.MISSING, row.status());
        assertNull(row.invoiceDescription());
        assertNull(row.quantityDifference());
        Discrepancy discrepancy = onlyDiscrepancy(result, DiscrepancyType.MISSING_ITEM);
        assertEquals("REF-77", discrepancy.referenceValue());
        assertNull(discrepancy.invoiceValue());
        assertNull(discrepancy.absoluteDifference());
        assertNull(discrepancy.percentageDifference());
    }

    @Test
    void invoiceOnlyLineProducesUnexpectedRowAndUsesCappedDescriptionWhenCodeIsAbsent() {
        UUID invoiceId = UUID.randomUUID();
        String description = "😀".repeat(300);
        LineItemMatch match = match(LineMatchStatus.UNMATCHED_INVOICE, null, invoiceId);

        LineReconciliationResult result = engine.reconcile(
                ANALYSIS_ID, List.of(match), Map.of(),
                Map.of(invoiceId, line(null, description, "1", "5", null, null, "5")),
                EXACT, true, CREATED);

        ReconciliationLine row = result.lines().getFirst();
        assertEquals(ReconciliationLineStatus.UNEXPECTED, row.status());
        assertNull(row.referenceDescription());
        assertNull(row.lineTotalDifference());
        Discrepancy discrepancy = onlyDiscrepancy(result, DiscrepancyType.UNEXPECTED_ITEM);
        assertEquals(255, discrepancy.invoiceValue().codePointCount(0, discrepancy.invoiceValue().length()));
        assertEquals("😀".repeat(255), discrepancy.invoiceValue());
    }

    @Test
    void aNeedsReviewMatchIsRejectedBeforeAnyReconciliationIsProduced() {
        UUID referenceId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        LineItemMatch match = match(LineMatchStatus.NEEDS_REVIEW, referenceId, invoiceId);

        assertThrows(IllegalStateException.class, () -> engine.reconcile(
                ANALYSIS_ID, List.of(match),
                Map.of(referenceId, line("R", "Reference", "1", "1", null, null, "1")),
                Map.of(invoiceId, line("I", "Invoice", "1", "1", null, null, "1")),
                EXACT, true, CREATED));
    }

    @Test
    void matchSetMustCoverEachConfirmedLineExactlyOnceAndBelongToRequestedAnalysis() {
        UUID referenceId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        ExtractedLineItem reference = line("R", "Reference", "1", "1", null, null, "1");
        ExtractedLineItem invoice = line("I", "Invoice", "1", "1", null, null, "1");
        LineItemMatch match = match(LineMatchStatus.MATCHED, referenceId, invoiceId);

        assertThrows(IllegalArgumentException.class, () -> engine.reconcile(
                ANALYSIS_ID, List.of(match), Map.of(referenceId, reference, UUID.randomUUID(), reference),
                Map.of(invoiceId, invoice), EXACT, true, CREATED));
        assertThrows(IllegalArgumentException.class, () -> engine.reconcile(
                ANALYSIS_ID, List.of(match), Map.of(referenceId, reference), Map.of(), EXACT, true, CREATED));
        assertThrows(IllegalArgumentException.class, () -> engine.reconcile(
                UUID.randomUUID(), List.of(match), Map.of(referenceId, reference),
                Map.of(invoiceId, invoice), EXACT, true, CREATED));
    }

    @Test
    void explanationsAreDeterministicNeutralAndLineDiscrepanciesReferenceTheirSnapshot() {
        LineReconciliationResult result = reconcile(
                line("SKU-1", "Widget", "1", "10", null, null, "10"),
                line("SKU-1", "Widget", "2", "12", null, null, "12"), EXACT, true);

        for (Discrepancy discrepancy : result.discrepancies()) {
            assertEquals(result.lines().getFirst().id(), discrepancy.reconciliationLineId());
            String explanation = discrepancy.explanation().toLowerCase();
            assertTrue(!explanation.contains("fraud") && !explanation.contains("scam")
                    && !explanation.contains("cheat") && !explanation.contains("overcharged"));
        }
        assertEquals("The invoiced quantity differs from the reference quantity.",
                result.discrepancies().getFirst().explanation());
    }

    private LineReconciliationResult reconcile(
            ExtractedLineItem reference,
            ExtractedLineItem invoice,
            PriceTolerance tolerance,
            boolean monetaryComparisonAllowed) {
        UUID referenceId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        return engine.reconcile(
                ANALYSIS_ID,
                List.of(match(LineMatchStatus.MATCHED, referenceId, invoiceId)),
                Map.of(referenceId, reference), Map.of(invoiceId, invoice),
                tolerance, monetaryComparisonAllowed, CREATED);
    }

    private static ExtractedLineItem line(
            String code, String description, String quantity, String unitPrice,
            String discount, String tax, String lineTotal) {
        return new ExtractedLineItem(0, code, description,
                decimal(quantity), null, decimal(unitPrice), decimal(discount), decimal(tax), decimal(lineTotal),
                null, null, null);
    }

    private static LineItemMatch match(LineMatchStatus status, UUID referenceId, UUID invoiceId) {
        return new LineItemMatch(
                UUID.randomUUID(), ANALYSIS_ID, referenceId, invoiceId, status,
                status == LineMatchStatus.NEEDS_REVIEW ? LineMatchMethod.FUZZY : LineMatchMethod.MANUAL,
                null, 0, null, CREATED, CREATED);
    }

    private static Discrepancy onlyDiscrepancy(LineReconciliationResult result, DiscrepancyType type) {
        assertEquals(1, result.discrepancies().stream().filter(item -> item.type() == type).count());
        return result.discrepancies().stream().filter(item -> item.type() == type).findFirst().orElseThrow();
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
