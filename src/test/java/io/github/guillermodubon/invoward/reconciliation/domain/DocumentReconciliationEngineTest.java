package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentReconciliationEngineTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-01T11:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();

    private final DocumentReconciliationEngine engine = new DocumentReconciliationEngine();

    @Test
    void equalNormalizedCurrenciesAllowHeaderComparisonsAndSignedAnalysisDifference() {
        DocumentReconciliationResult result = reconcile(
                document("usd", "100", "5", "10", "105"),
                document("USD", "110", "5", "10", "115"), PriceTolerance.exactMatch());

        assertTrue(result.currenciesCompatible());
        assertDecimalEquals("10", result.analysisDifference());
        assertEquals(List.of(
                        DiscrepancyType.SUBTOTAL_MISMATCH,
                        DiscrepancyType.TOTAL_MISMATCH),
                result.discrepancies().stream().map(Discrepancy::type).toList());
    }

    @Test
    void currencyMismatchProducesOneCurrencyDiscrepancyAndSuppressesAllMonetaryResults() {
        DocumentReconciliationResult result = reconcile(
                document("USD", "100", "1", "10", "111"),
                document("EUR", "200", "3", "30", "233"), PriceTolerance.exactMatch());

        assertFalse(result.currenciesCompatible());
        assertNull(result.analysisDifference());
        Discrepancy currency = onlyDiscrepancy(result, DiscrepancyType.CURRENCY_MISMATCH);
        assertEquals("USD", currency.referenceValue());
        assertEquals("EUR", currency.invoiceValue());
        assertNull(currency.absoluteDifference());
        assertNull(currency.percentageDifference());
        assertEquals("The reference and invoice use different currencies.", currency.explanation());
        assertNull(currency.reconciliationLineId());
    }

    @Test
    void eitherMissingCurrencyIsAMismatchButBothMissingCurrenciesDoNotBlockComparison() {
        DocumentReconciliationResult missingReference = reconcile(
                document(null, "100", null, null, "100"),
                document("USD", "100", null, null, "100"), PriceTolerance.exactMatch());
        DocumentReconciliationResult missingInvoice = reconcile(
                document("USD", "100", null, null, "100"),
                document(null, "100", null, null, "100"), PriceTolerance.exactMatch());
        DocumentReconciliationResult bothMissing = reconcile(
                document(null, "100", null, null, "100"),
                document(null, "100", null, null, "100"), PriceTolerance.exactMatch());

        assertFalse(missingReference.currenciesCompatible());
        assertNull(onlyDiscrepancy(missingReference, DiscrepancyType.CURRENCY_MISMATCH).referenceValue());
        assertEquals("USD", missingReference.discrepancies().getFirst().invoiceValue());
        assertEquals("USD", onlyDiscrepancy(missingInvoice, DiscrepancyType.CURRENCY_MISMATCH).referenceValue());
        assertNull(missingInvoice.discrepancies().getFirst().invoiceValue());
        assertTrue(bothMissing.currenciesCompatible());
        assertTrue(bothMissing.discrepancies().isEmpty());
    }

    @Test
    void mapsAllHeaderAmountDifferencesToExistingDiscrepancyTypes() {
        DocumentReconciliationResult result = reconcile(
                document("USD", "100", "2", "3", "105"),
                document("USD", "110", "4", "6", "120"), PriceTolerance.exactMatch());

        assertEquals(List.of(
                        DiscrepancyType.SUBTOTAL_MISMATCH,
                        DiscrepancyType.DISCOUNT_MISMATCH,
                        DiscrepancyType.TAX_MISMATCH,
                        DiscrepancyType.TOTAL_MISMATCH),
                result.discrepancies().stream().map(Discrepancy::type).toList());
        assertTrue(result.discrepancies().stream().allMatch(discrepancy ->
                discrepancy.reconciliationLineId() == null
                        && discrepancy.resolutionStatus() == DiscrepancyResolutionStatus.OPEN
                        && discrepancy.resolutionReason() == null
                        && discrepancy.resolutionNote() == null
                        && discrepancy.resolvedAt() == null
                        && discrepancy.version() == 0));
    }

    @Test
    void exactToleranceBoundaryIsAcceptedForHeaderMoney() {
        PriceTolerance onePercent = new PriceTolerance(BigDecimal.ONE, null);
        DocumentReconciliationResult boundary = reconcile(
                document("USD", "100", null, null, "100"),
                document("USD", "101", null, null, "100"), onePercent);
        DocumentReconciliationResult outside = reconcile(
                document("USD", "100", null, null, "100"),
                document("USD", "101.0001", null, null, "100"), onePercent);

        assertTrue(boundary.discrepancies().isEmpty());
        assertDecimalEquals("1.0001", outside.discrepancies().getFirst().absoluteDifference());
        assertEquals(DiscrepancyType.SUBTOTAL_MISMATCH, outside.discrepancies().getFirst().type());
    }

    @Test
    void nullNullHeaderValuesAreEqualButNullValueCreatesMismatchWithoutFabricatedArithmetic() {
        DocumentReconciliationResult result = reconcile(
                document("USD", null, null, null, null),
                document("USD", "10", null, null, null), PriceTolerance.exactMatch());

        Discrepancy subtotal = onlyDiscrepancy(result, DiscrepancyType.SUBTOTAL_MISMATCH);
        assertNull(subtotal.referenceValue());
        assertEquals("10", subtotal.invoiceValue());
        assertNull(subtotal.absoluteDifference());
        assertNull(subtotal.percentageDifference());
        assertNull(result.analysisDifference());
    }

    @Test
    void zeroReferenceHasNullPercentageAndAnalysisDifferenceRetainsInvoiceMinusReferenceSign() {
        DocumentReconciliationResult result = reconcile(
                document("USD", null, null, null, "0"),
                document("USD", null, null, null, "2"), PriceTolerance.exactMatch());

        Discrepancy total = onlyDiscrepancy(result, DiscrepancyType.TOTAL_MISMATCH);
        assertNull(total.percentageDifference());
        assertDecimalEquals("2", result.analysisDifference());

        DocumentReconciliationResult negative = reconcile(
                document("USD", null, null, null, "100"),
                document("USD", null, null, null, "90"), PriceTolerance.exactMatch());
        assertDecimalEquals("-10", negative.analysisDifference());
    }

    @Test
    void analysisDifferenceIsNotSuppressedByToleranceWhenCurrenciesAreCompatible() {
        DocumentReconciliationResult result = reconcile(
                document("USD", null, null, null, "100"),
                document("USD", null, null, null, "101"),
                new PriceTolerance(BigDecimal.ONE, null));

        assertTrue(result.discrepancies().isEmpty());
        assertDecimalEquals("1", result.analysisDifference());
    }

    @Test
    void requiresBothExtractionSnapshotsToBeConfirmed() {
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, null, null, null, "USD", null, null, null, null, List.of());

        assertThrows(IllegalArgumentException.class, () -> engine.reconcile(
                ANALYSIS_ID, draft, document("USD", null, null, null, null),
                PriceTolerance.exactMatch(), CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> engine.reconcile(
                ANALYSIS_ID, document("USD", null, null, null, null), draft,
                PriceTolerance.exactMatch(), CREATED_AT));
    }

    private DocumentReconciliationResult reconcile(
            ExtractedDocument reference, ExtractedDocument invoice, PriceTolerance tolerance) {
        return engine.reconcile(ANALYSIS_ID, reference, invoice, tolerance, CREATED_AT);
    }

    private static ExtractedDocument document(
            String currency, String subtotal, String discount, String tax, String total) {
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Vendor", "INV-1", null, currency,
                decimal(subtotal), decimal(discount), decimal(tax), decimal(total), List.<ExtractedLineItem>of());
        return draft.confirm(CONFIRMED_AT);
    }

    private static Discrepancy onlyDiscrepancy(DocumentReconciliationResult result, DiscrepancyType type) {
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
