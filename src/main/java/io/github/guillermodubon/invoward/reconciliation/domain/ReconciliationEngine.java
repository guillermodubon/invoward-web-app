package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure deterministic rules for turning confirmed line matches into reconciliation snapshots and findings. */
public final class ReconciliationEngine {

    private static final int MAX_DISCREPANCY_VALUE_CODE_POINTS = 255;

    private final FinancialDifferenceCalculator differenceCalculator;
    private final MonetaryToleranceEvaluator toleranceEvaluator;
    private final CanonicalValueFormatter valueFormatter;

    public ReconciliationEngine() {
        this(new FinancialDifferenceCalculator(), new MonetaryToleranceEvaluator(), new CanonicalValueFormatter());
    }

    ReconciliationEngine(
            FinancialDifferenceCalculator differenceCalculator,
            MonetaryToleranceEvaluator toleranceEvaluator,
            CanonicalValueFormatter valueFormatter) {
        this.differenceCalculator = Objects.requireNonNull(differenceCalculator, "differenceCalculator");
        this.toleranceEvaluator = Objects.requireNonNull(toleranceEvaluator, "toleranceEvaluator");
        this.valueFormatter = Objects.requireNonNull(valueFormatter, "valueFormatter");
    }

    /**
     * Reconciles each confirmed match once and requires the match set to cover all confirmed lines on both sides.
     * The caller supplies whether currencies permit monetary comparisons; document-level currency findings are
     * deliberately outside this line engine.
     */
    public LineReconciliationResult reconcile(
            UUID analysisId,
            List<LineItemMatch> matches,
            Map<UUID, ExtractedLineItem> referenceLines,
            Map<UUID, ExtractedLineItem> invoiceLines,
            PriceTolerance tolerance,
            boolean monetaryComparisonAllowed,
            Instant createdAt) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(matches, "matches must not be null");
        Objects.requireNonNull(referenceLines, "referenceLines must not be null");
        Objects.requireNonNull(invoiceLines, "invoiceLines must not be null");
        Objects.requireNonNull(tolerance, "tolerance must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        List<LineItemMatch> matchSnapshot = List.copyOf(matches);
        Map<UUID, ExtractedLineItem> referenceSnapshot = Map.copyOf(referenceLines);
        Map<UUID, ExtractedLineItem> invoiceSnapshot = Map.copyOf(invoiceLines);
        validateInputMaps(referenceSnapshot, invoiceSnapshot);
        List<ReconciliationLine> lines = new ArrayList<>(matchSnapshot.size());
        List<Discrepancy> discrepancies = new ArrayList<>();
        Set<UUID> matchIds = new HashSet<>();
        Set<UUID> coveredReferenceIds = new HashSet<>();
        Set<UUID> coveredInvoiceIds = new HashSet<>();

        for (LineItemMatch match : matchSnapshot) {
            if (!analysisId.equals(match.analysisId())) {
                throw new IllegalArgumentException("all line matches must belong to the requested Analysis");
            }
            if (!matchIds.add(match.id())) {
                throw new IllegalArgumentException("line match set must not contain duplicate match ids");
            }
            if (match.status() == LineMatchStatus.NEEDS_REVIEW) {
                throw new IllegalStateException("matching input is not ready for reconciliation");
            }

            ExtractedLineItem reference = resolveLine(
                    referenceSnapshot, match.referenceLineItemId(), coveredReferenceIds, "reference");
            ExtractedLineItem invoice = resolveLine(
                    invoiceSnapshot, match.invoiceLineItemId(), coveredInvoiceIds, "invoice");
            switch (match.status()) {
                case MATCHED -> reconcileMatched(
                        analysisId, match, reference, invoice, tolerance, monetaryComparisonAllowed,
                        createdAt, lines, discrepancies);
                case UNMATCHED_REFERENCE -> reconcileUnmatchedReference(
                        analysisId, match, reference, createdAt, lines, discrepancies);
                case UNMATCHED_INVOICE -> reconcileUnmatchedInvoice(
                        analysisId, match, invoice, createdAt, lines, discrepancies);
                case NEEDS_REVIEW -> throw new IllegalStateException("matching input is not ready for reconciliation");
            }
        }

        if (!coveredReferenceIds.equals(referenceSnapshot.keySet())
                || !coveredInvoiceIds.equals(invoiceSnapshot.keySet())) {
            throw new IllegalArgumentException("confirmed lines must be covered exactly once by the match set");
        }
        return new LineReconciliationResult(analysisId, lines, discrepancies);
    }

    private static void validateInputMaps(
            Map<UUID, ExtractedLineItem> referenceLines,
            Map<UUID, ExtractedLineItem> invoiceLines) {
        Set<UUID> allIds = new HashSet<>();
        for (Map.Entry<UUID, ExtractedLineItem> entry : referenceLines.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "reference line id must not be null");
            Objects.requireNonNull(entry.getValue(), "reference line must not be null");
            if (!allIds.add(entry.getKey())) {
                throw new IllegalArgumentException("confirmed line ids must be unique across both documents");
            }
        }
        for (Map.Entry<UUID, ExtractedLineItem> entry : invoiceLines.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "invoice line id must not be null");
            Objects.requireNonNull(entry.getValue(), "invoice line must not be null");
            if (!allIds.add(entry.getKey())) {
                throw new IllegalArgumentException("confirmed line ids must be unique across both documents");
            }
        }
    }

    private static ExtractedLineItem resolveLine(
            Map<UUID, ExtractedLineItem> lines,
            UUID lineId,
            Set<UUID> coveredIds,
            String side) {
        if (lineId == null) {
            return null;
        }
        ExtractedLineItem line = lines.get(lineId);
        if (line == null) {
            throw new IllegalArgumentException("line match references an unknown " + side + " line");
        }
        if (!coveredIds.add(lineId)) {
            throw new IllegalArgumentException("confirmed line must appear exactly once in the match set");
        }
        return line;
    }

    private void reconcileMatched(
            UUID analysisId,
            LineItemMatch match,
            ExtractedLineItem reference,
            ExtractedLineItem invoice,
            PriceTolerance tolerance,
            boolean monetaryComparisonAllowed,
            Instant createdAt,
            List<ReconciliationLine> outputLines,
            List<Discrepancy> outputDiscrepancies) {
        UUID reconciliationLineId = UUID.randomUUID();
        List<Discrepancy> lineDiscrepancies = new ArrayList<>();

        FinancialDifference quantityDifference = differenceCalculator.calculate(reference.quantity(), invoice.quantity());
        if (quantityDifference.signedDifference().signum() != 0) {
            lineDiscrepancies.add(discrepancy(
                    analysisId, reconciliationLineId, DiscrepancyType.QUANTITY_MISMATCH,
                    reference.quantity(), invoice.quantity(), quantityDifference,
                    "The invoiced quantity differs from the reference quantity.", createdAt));
        }

        FinancialDifference unitPriceDifference = null;
        if (monetaryComparisonAllowed) {
            unitPriceDifference = differenceCalculator.calculate(reference.unitPrice(), invoice.unitPrice());
            addMonetaryDiscrepancyIfOutsideTolerance(
                    lineDiscrepancies, analysisId, reconciliationLineId, DiscrepancyType.UNIT_PRICE_MISMATCH,
                    reference.unitPrice(), invoice.unitPrice(), unitPriceDifference, tolerance,
                    "The invoiced unit price differs from the reference price beyond the configured tolerance.",
                    createdAt);
            addMonetaryDiscrepancyIfOutsideTolerance(
                    lineDiscrepancies, analysisId, reconciliationLineId, DiscrepancyType.DISCOUNT_MISMATCH,
                    reference.discountAmount(), invoice.discountAmount(),
                    differenceCalculator.calculate(reference.discountAmount(), invoice.discountAmount()), tolerance,
                    "The invoiced discount differs from the reference discount beyond the configured tolerance.",
                    createdAt);
            addMonetaryDiscrepancyIfOutsideTolerance(
                    lineDiscrepancies, analysisId, reconciliationLineId, DiscrepancyType.TAX_MISMATCH,
                    reference.taxAmount(), invoice.taxAmount(),
                    differenceCalculator.calculate(reference.taxAmount(), invoice.taxAmount()), tolerance,
                    "The invoiced tax differs from the reference tax beyond the configured tolerance.",
                    createdAt);
        }

        FinancialDifference lineTotalDifference = monetaryComparisonAllowed
                ? differenceCalculator.calculate(reference.lineTotal(), invoice.lineTotal())
                : null;
        if (monetaryComparisonAllowed
                && !toleranceEvaluator.isWithinTolerance(reference.lineTotal(), invoice.lineTotal(), tolerance)
                && lineDiscrepancies.isEmpty()) {
            lineDiscrepancies.add(discrepancy(
                    analysisId, reconciliationLineId, DiscrepancyType.TOTAL_MISMATCH,
                    reference.lineTotal(), invoice.lineTotal(), lineTotalDifference,
                    "The invoiced line total differs from the reference line total beyond the configured tolerance.",
                    createdAt));
        }

        ReconciliationLine line = new ReconciliationLine(
                reconciliationLineId, analysisId, match.id(),
                lineDiscrepancies.isEmpty() ? ReconciliationLineStatus.MATCHED : ReconciliationLineStatus.DIFFERENT,
                reference.itemCode(), invoice.itemCode(), reference.description(), invoice.description(),
                reference.quantity(), invoice.quantity(), reference.unitPrice(), invoice.unitPrice(),
                reference.lineTotal(), invoice.lineTotal(), quantityDifference.signedDifference(),
                unitPriceDifference == null ? null : unitPriceDifference.signedDifference(),
                lineTotalDifference == null ? null : lineTotalDifference.signedDifference(), createdAt);

        outputLines.add(line);
        outputDiscrepancies.addAll(lineDiscrepancies);
    }

    private void addMonetaryDiscrepancyIfOutsideTolerance(
            List<Discrepancy> discrepancies,
            UUID analysisId,
            UUID reconciliationLineId,
            DiscrepancyType type,
            BigDecimal reference,
            BigDecimal invoice,
            FinancialDifference difference,
            PriceTolerance tolerance,
            String explanation,
            Instant createdAt) {
        if (!toleranceEvaluator.isWithinTolerance(reference, invoice, tolerance)) {
            discrepancies.add(discrepancy(
                    analysisId, reconciliationLineId, type, reference, invoice, difference, explanation, createdAt));
        }
    }

    private void reconcileUnmatchedReference(
            UUID analysisId,
            LineItemMatch match,
            ExtractedLineItem reference,
            Instant createdAt,
            List<ReconciliationLine> outputLines,
            List<Discrepancy> outputDiscrepancies) {
        ReconciliationLine line = new ReconciliationLine(
                UUID.randomUUID(), analysisId, match.id(), ReconciliationLineStatus.MISSING,
                reference.itemCode(), null, reference.description(), null,
                reference.quantity(), null, reference.unitPrice(), null, reference.lineTotal(), null,
                null, null, null, createdAt);
        outputLines.add(line);
        outputDiscrepancies.add(newDiscrepancy(
                analysisId, line.id(), DiscrepancyType.MISSING_ITEM,
                safeIdentifier(reference), null, null, null,
                "This reference item has no matched invoice line.", createdAt));
    }

    private void reconcileUnmatchedInvoice(
            UUID analysisId,
            LineItemMatch match,
            ExtractedLineItem invoice,
            Instant createdAt,
            List<ReconciliationLine> outputLines,
            List<Discrepancy> outputDiscrepancies) {
        ReconciliationLine line = new ReconciliationLine(
                UUID.randomUUID(), analysisId, match.id(), ReconciliationLineStatus.UNEXPECTED,
                null, invoice.itemCode(), null, invoice.description(),
                null, invoice.quantity(), null, invoice.unitPrice(), null, invoice.lineTotal(),
                null, null, null, createdAt);
        outputLines.add(line);
        outputDiscrepancies.add(newDiscrepancy(
                analysisId, line.id(), DiscrepancyType.UNEXPECTED_ITEM,
                null, safeIdentifier(invoice), null, null,
                "This invoice item has no matched reference line.", createdAt));
    }

    private Discrepancy discrepancy(
            UUID analysisId,
            UUID reconciliationLineId,
            DiscrepancyType type,
            BigDecimal reference,
            BigDecimal invoice,
            FinancialDifference difference,
            String explanation,
            Instant createdAt) {
        return newDiscrepancy(
                analysisId, reconciliationLineId, type,
                valueFormatter.format(reference), valueFormatter.format(invoice),
                difference == null ? null : difference.absoluteDifference(),
                difference == null ? null : difference.percentageDifference(), explanation, createdAt);
    }

    private static Discrepancy newDiscrepancy(
            UUID analysisId,
            UUID reconciliationLineId,
            DiscrepancyType type,
            String referenceValue,
            String invoiceValue,
            BigDecimal absoluteDifference,
            BigDecimal percentageDifference,
            String explanation,
            Instant createdAt) {
        return new Discrepancy(
                UUID.randomUUID(), analysisId, reconciliationLineId, type,
                referenceValue, invoiceValue, absoluteDifference, percentageDifference,
                explanation, DiscrepancyResolutionStatus.OPEN, null, null,
                0, null, createdAt, createdAt);
    }

    private static String safeIdentifier(ExtractedLineItem line) {
        String value = line.itemCode() == null ? line.description() : line.itemCode();
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= MAX_DISCREPANCY_VALUE_CODE_POINTS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_DISCREPANCY_VALUE_CODE_POINTS));
    }
}
