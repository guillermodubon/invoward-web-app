package io.github.guillermodubon.invoward.sharing.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Strictly whitelisted, provider-neutral snapshot exposed by a public share link. */
public record PublicSharedReport(
        Instant reportGeneratedAt,
        String supplierName,
        String referenceType,
        String referenceNumber,
        String invoiceNumber,
        String currency,
        AnalysisReconciliationStatus reconciliationStatus,
        BigDecimal referenceTotal,
        BigDecimal invoiceTotal,
        BigDecimal difference,
        Tolerance tolerance,
        Summary summary,
        List<Line> lines,
        List<PublicDiscrepancy> discrepancies) {

    public PublicSharedReport {
        Objects.requireNonNull(reportGeneratedAt, "reportGeneratedAt must not be null");
        Objects.requireNonNull(reconciliationStatus, "reconciliationStatus must not be null");
        Objects.requireNonNull(tolerance, "tolerance must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        discrepancies = List.copyOf(Objects.requireNonNull(discrepancies, "discrepancies must not be null"));
        if (summary.lineCount() != lines.size() || summary.discrepancyCount() != discrepancies.size()) {
            throw new IllegalArgumentException("public summary counts must match the public rows");
        }
    }

    /** Public tolerance values without exposing the Analysis aggregate. */
    public record Tolerance(BigDecimal percentage, BigDecimal absolute) {
        public Tolerance {
            Objects.requireNonNull(percentage, "percentage must not be null");
        }
    }

    /** Counts disclosed by the public contract. */
    public record Summary(
            long lineCount,
            long matchedCount,
            long differentCount,
            long missingCount,
            long unexpectedCount,
            long discrepancyCount) {

        public Summary {
            requireNonnegative(lineCount, "lineCount");
            requireNonnegative(matchedCount, "matchedCount");
            requireNonnegative(differentCount, "differentCount");
            requireNonnegative(missingCount, "missingCount");
            requireNonnegative(unexpectedCount, "unexpectedCount");
            requireNonnegative(discrepancyCount, "discrepancyCount");
            long representedLines = Math.addExact(Math.addExact(matchedCount, differentCount),
                    Math.addExact(missingCount, unexpectedCount));
            if (representedLines != lineCount) {
                throw new IllegalArgumentException("line counts must sum to lineCount");
            }
        }

        private static void requireNonnegative(long value, String field) {
            if (value < 0) {
                throw new IllegalArgumentException(field + " must not be negative");
            }
        }
    }

    /** Persisted line comparison values; persistence IDs and evidence are deliberately absent. */
    public record Line(
            ReconciliationLineStatus status,
            String referenceItemCode,
            String invoiceItemCode,
            String referenceDescription,
            String invoiceDescription,
            BigDecimal referenceQuantity,
            BigDecimal invoiceQuantity,
            BigDecimal referenceUnitPrice,
            BigDecimal invoiceUnitPrice,
            BigDecimal referenceLineTotal,
            BigDecimal invoiceLineTotal,
            BigDecimal quantityDifference,
            BigDecimal unitPriceDifference,
            BigDecimal lineTotalDifference) {
        public Line {
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /** Public discrepancy values and current resolution status only. */
    public record PublicDiscrepancy(
            DiscrepancyType type,
            String explanation,
            String referenceValue,
            String invoiceValue,
            BigDecimal absoluteDifference,
            BigDecimal percentageDifference,
            DiscrepancyResolutionStatus resolutionStatus) {
        public PublicDiscrepancy {
            Objects.requireNonNull(type, "type must not be null");
            if (explanation == null || explanation.isBlank()) {
                throw new IllegalArgumentException("explanation must not be blank");
            }
            Objects.requireNonNull(resolutionStatus, "resolutionStatus must not be null");
        }
    }
}
