package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pure deterministic comparison of confirmed document headers. */
public final class DocumentReconciliationEngine {

    private final FinancialDifferenceCalculator differenceCalculator;
    private final MonetaryToleranceEvaluator toleranceEvaluator;
    private final CanonicalValueFormatter valueFormatter;

    public DocumentReconciliationEngine() {
        this(new FinancialDifferenceCalculator(), new MonetaryToleranceEvaluator(), new CanonicalValueFormatter());
    }

    DocumentReconciliationEngine(
            FinancialDifferenceCalculator differenceCalculator,
            MonetaryToleranceEvaluator toleranceEvaluator,
            CanonicalValueFormatter valueFormatter) {
        this.differenceCalculator = Objects.requireNonNull(differenceCalculator, "differenceCalculator");
        this.toleranceEvaluator = Objects.requireNonNull(toleranceEvaluator, "toleranceEvaluator");
        this.valueFormatter = Objects.requireNonNull(valueFormatter, "valueFormatter");
    }

    public DocumentReconciliationResult reconcile(
            UUID analysisId,
            ExtractedDocument reference,
            ExtractedDocument invoice,
            PriceTolerance tolerance,
            Instant createdAt) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(invoice, "invoice must not be null");
        Objects.requireNonNull(tolerance, "tolerance must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        requireConfirmed(reference, "reference");
        requireConfirmed(invoice, "invoice");

        boolean currenciesCompatible = Objects.equals(reference.currency(), invoice.currency());
        List<Discrepancy> discrepancies = new ArrayList<>();
        if (!currenciesCompatible) {
            discrepancies.add(newDiscrepancy(
                    analysisId, DiscrepancyType.CURRENCY_MISMATCH,
                    reference.currency(), invoice.currency(), null, null,
                    "The reference and invoice use different currencies.", createdAt));
            return new DocumentReconciliationResult(analysisId, false, null, discrepancies);
        }

        addMonetaryDiscrepancyIfOutsideTolerance(
                discrepancies, analysisId, DiscrepancyType.SUBTOTAL_MISMATCH,
                reference.subtotal(), invoice.subtotal(), tolerance,
                "The document subtotals differ beyond the configured tolerance.", createdAt);
        addMonetaryDiscrepancyIfOutsideTolerance(
                discrepancies, analysisId, DiscrepancyType.DISCOUNT_MISMATCH,
                reference.discountTotal(), invoice.discountTotal(), tolerance,
                "The document discounts differ beyond the configured tolerance.", createdAt);
        addMonetaryDiscrepancyIfOutsideTolerance(
                discrepancies, analysisId, DiscrepancyType.TAX_MISMATCH,
                reference.taxTotal(), invoice.taxTotal(), tolerance,
                "The document taxes differ beyond the configured tolerance.", createdAt);
        addMonetaryDiscrepancyIfOutsideTolerance(
                discrepancies, analysisId, DiscrepancyType.TOTAL_MISMATCH,
                reference.total(), invoice.total(), tolerance,
                "The document totals differ beyond the configured tolerance.", createdAt);

        BigDecimal analysisDifference = differenceCalculator.calculate(reference.total(), invoice.total())
                .signedDifference();
        return new DocumentReconciliationResult(analysisId, true, analysisDifference, discrepancies);
    }

    private void addMonetaryDiscrepancyIfOutsideTolerance(
            List<Discrepancy> discrepancies,
            UUID analysisId,
            DiscrepancyType type,
            BigDecimal reference,
            BigDecimal invoice,
            PriceTolerance tolerance,
            String explanation,
            Instant createdAt) {
        if (toleranceEvaluator.isWithinTolerance(reference, invoice, tolerance)) {
            return;
        }
        FinancialDifference difference = differenceCalculator.calculate(reference, invoice);
        discrepancies.add(newDiscrepancy(
                analysisId, type, valueFormatter.format(reference), valueFormatter.format(invoice),
                difference.absoluteDifference(), difference.percentageDifference(), explanation, createdAt));
    }

    private static void requireConfirmed(ExtractedDocument document, String side) {
        if (document.status() != ExtractionStatus.CONFIRMED) {
            throw new IllegalArgumentException(side + " extraction must be confirmed before reconciliation");
        }
    }

    private static Discrepancy newDiscrepancy(
            UUID analysisId,
            DiscrepancyType type,
            String referenceValue,
            String invoiceValue,
            BigDecimal absoluteDifference,
            BigDecimal percentageDifference,
            String explanation,
            Instant createdAt) {
        return new Discrepancy(
                UUID.randomUUID(), analysisId, null, type, referenceValue, invoiceValue,
                absoluteDifference, percentageDifference, explanation,
                DiscrepancyResolutionStatus.OPEN, null, null, 0, null, createdAt, createdAt);
    }
}
