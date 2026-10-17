package io.github.guillermodubon.invoward.reporting.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Immutable, provider-neutral presentation snapshot of persisted reconciliation data. */
public record ReportSnapshot(
        String supplierName,
        String invoiceNumber,
        String referenceNumber,
        String referenceType,
        LocalDate invoiceDate,
        LocalDate referenceDate,
        String currency,
        AnalysisReconciliationStatus reconciliationStatus,
        BigDecimal referenceTotal,
        BigDecimal invoiceTotal,
        BigDecimal difference,
        BigDecimal priceTolerancePercent,
        BigDecimal priceToleranceAbsolute,
        boolean duplicateWarning,
        Summary summary,
        List<ReportLineSnapshot> lines,
        List<ReportDiscrepancySnapshot> discrepancies,
        Instant generatedAt) {

    public ReportSnapshot {
        Objects.requireNonNull(reconciliationStatus, "reconciliationStatus must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        discrepancies = List.copyOf(Objects.requireNonNull(discrepancies, "discrepancies must not be null"));
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
    }

    /** Summary counts copied from the persisted reconciliation projection. */
    public record Summary(
            long lineCount,
            long matchedCount,
            long differentCount,
            long missingCount,
            long unexpectedCount,
            long openDiscrepancyCount,
            long acceptedDiscrepancyCount,
            long resolvedDiscrepancyCount) {

        public Summary {
            if (lineCount < 0 || matchedCount < 0 || differentCount < 0 || missingCount < 0
                    || unexpectedCount < 0 || openDiscrepancyCount < 0 || acceptedDiscrepancyCount < 0
                    || resolvedDiscrepancyCount < 0) {
                throw new IllegalArgumentException("report summary counts must not be negative");
            }
            long representedLines = Math.addExact(Math.addExact(matchedCount, differentCount),
                    Math.addExact(missingCount, unexpectedCount));
            if (representedLines != lineCount) {
                throw new IllegalArgumentException("report line counts must sum to lineCount");
            }
        }
    }

    @Override
    public String toString() {
        return "ReportSnapshot[reconciliationStatus=" + reconciliationStatus + ", lineCount="
                + lines.size() + ", discrepancyCount=" + discrepancies.size() + ", generatedAt=" + generatedAt + "]";
    }
}
