package io.github.guillermodubon.invoward.reporting.application.model;

import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;

import java.math.BigDecimal;
import java.util.Objects;

/** Report-safe persisted discrepancy values, including its current resolution state. */
public record ReportDiscrepancySnapshot(
        DiscrepancyType type,
        String referenceValue,
        String invoiceValue,
        BigDecimal absoluteDifference,
        BigDecimal percentageDifference,
        String explanation,
        DiscrepancyResolutionStatus resolutionStatus,
        DiscrepancyResolutionReason resolutionReason,
        String resolutionNote) {

    public ReportDiscrepancySnapshot {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus must not be null");
    }

    @Override
    public String toString() {
        return "ReportDiscrepancySnapshot[type=" + type + ", resolutionStatus=" + resolutionStatus + "]";
    }
}
