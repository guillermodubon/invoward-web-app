package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.Numeric19_4;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Safe provider-neutral result projection shared by reconcile and read use cases. */
public record ReconciliationResult(
        UUID analysisId,
        AnalysisStatus analysisStatus,
        AnalysisReconciliationStatus reconciliationStatus,
        PriceTolerance priceTolerance,
        BigDecimal referenceTotal,
        BigDecimal invoiceTotal,
        BigDecimal difference,
        DuplicateInvoiceWarning duplicateInvoice,
        ReconciliationSummary summary,
        List<ReconciliationLine> lines,
        List<Discrepancy> discrepancies) {

    public ReconciliationResult {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(analysisStatus, "analysisStatus must not be null");
        Objects.requireNonNull(reconciliationStatus, "reconciliationStatus must not be null");
        Objects.requireNonNull(priceTolerance, "priceTolerance must not be null");
        Objects.requireNonNull(duplicateInvoice, "duplicateInvoice must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        lines = List.copyOf(lines);
        discrepancies = List.copyOf(discrepancies);

        if (referenceTotal != null && referenceTotal.signum() < 0) {
            throw new IllegalArgumentException("referenceTotal must not be negative");
        }
        if (invoiceTotal != null && invoiceTotal.signum() < 0) {
            throw new IllegalArgumentException("invoiceTotal must not be negative");
        }
        Numeric19_4.requireCompatible(referenceTotal, "referenceTotal");
        Numeric19_4.requireCompatible(invoiceTotal, "invoiceTotal");
        Numeric19_4.requireCompatible(difference, "difference");
        for (ReconciliationLine line : lines) {
            if (!analysisId.equals(line.analysisId())) {
                throw new IllegalArgumentException("all result lines must belong to the result Analysis");
            }
        }
        for (Discrepancy discrepancy : discrepancies) {
            if (!analysisId.equals(discrepancy.analysisId())) {
                throw new IllegalArgumentException("all result discrepancies must belong to the result Analysis");
            }
        }
        validateSummary(lines, discrepancies, summary);
    }

    private static void validateSummary(
            List<ReconciliationLine> lines,
            List<Discrepancy> discrepancies,
            ReconciliationSummary summary) {
        long matched = lines.stream().filter(line -> line.status() == ReconciliationLineStatus.MATCHED).count();
        long different = lines.stream().filter(line -> line.status() == ReconciliationLineStatus.DIFFERENT).count();
        long missing = lines.stream().filter(line -> line.status() == ReconciliationLineStatus.MISSING).count();
        long unexpected = lines.stream().filter(line -> line.status() == ReconciliationLineStatus.UNEXPECTED).count();
        long open = count(discrepancies, DiscrepancyResolutionStatus.OPEN);
        long accepted = count(discrepancies, DiscrepancyResolutionStatus.ACCEPTED);
        long resolved = count(discrepancies, DiscrepancyResolutionStatus.RESOLVED);
        if (summary.lineCount() != lines.size() || summary.matchedCount() != matched
                || summary.differentCount() != different || summary.missingCount() != missing
                || summary.unexpectedCount() != unexpected || summary.openDiscrepancyCount() != open
                || summary.acceptedDiscrepancyCount() != accepted
                || summary.resolvedDiscrepancyCount() != resolved) {
            throw new IllegalArgumentException("summary counts must match the result contents");
        }
    }

    private static long count(List<Discrepancy> discrepancies, DiscrepancyResolutionStatus status) {
        return discrepancies.stream().filter(discrepancy -> discrepancy.resolutionStatus() == status).count();
    }

    @Override
    public String toString() {
        return "ReconciliationResult[analysisId=" + analysisId + ", analysisStatus=" + analysisStatus
                + ", reconciliationStatus=" + reconciliationStatus + ", lineCount=" + lines.size()
                + ", discrepancyCount=" + discrepancies.size() + "]";
    }
}
