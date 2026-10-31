package io.github.guillermodubon.invoward.sharing.api.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Explicit JSON whitelist for public shared reconciliation results. */
public record PublicSharedReportResponse(
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
        ToleranceResponse tolerance,
        SummaryResponse summary,
        List<LineResponse> lines,
        List<DiscrepancyResponse> discrepancies) {

    public PublicSharedReportResponse {
        Objects.requireNonNull(reportGeneratedAt, "reportGeneratedAt must not be null");
        Objects.requireNonNull(reconciliationStatus, "reconciliationStatus must not be null");
        Objects.requireNonNull(tolerance, "tolerance must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        discrepancies = List.copyOf(Objects.requireNonNull(discrepancies, "discrepancies must not be null"));
        if (summary.lineCount() != lines.size() || summary.discrepancyCount() != discrepancies.size()) {
            throw new IllegalArgumentException("response summary counts must match the response rows");
        }
    }

    public record ToleranceResponse(BigDecimal percentage, BigDecimal absolute) {
        public ToleranceResponse {
            Objects.requireNonNull(percentage, "percentage must not be null");
        }
    }

    public record SummaryResponse(
            long lineCount,
            long matchedCount,
            long differentCount,
            long missingCount,
            long unexpectedCount,
            long discrepancyCount) {
    }

    public record LineResponse(
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
        public LineResponse {
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    public record DiscrepancyResponse(
            DiscrepancyType type,
            String explanation,
            String referenceValue,
            String invoiceValue,
            BigDecimal absoluteDifference,
            BigDecimal percentageDifference,
            DiscrepancyResolutionStatus resolutionStatus) {
        public DiscrepancyResponse {
            Objects.requireNonNull(type, "type must not be null");
            if (explanation == null || explanation.isBlank()) {
                throw new IllegalArgumentException("explanation must not be blank");
            }
            Objects.requireNonNull(resolutionStatus, "resolutionStatus must not be null");
        }
    }
}
