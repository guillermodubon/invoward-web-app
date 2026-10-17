package io.github.guillermodubon.invoward.reporting.application.model;

import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;

import java.math.BigDecimal;
import java.util.Objects;

/** Report-safe line comparison values copied from an immutable reconciliation line. */
public record ReportLineSnapshot(
        ReconciliationLineStatus status,
        String referenceCode,
        String referenceDescription,
        String invoiceCode,
        String invoiceDescription,
        BigDecimal referenceQuantity,
        BigDecimal invoiceQuantity,
        BigDecimal quantityDifference,
        BigDecimal referenceUnitPrice,
        BigDecimal invoiceUnitPrice,
        BigDecimal unitPriceDifference,
        BigDecimal referenceLineTotal,
        BigDecimal invoiceLineTotal,
        BigDecimal lineTotalDifference) {

    public ReportLineSnapshot {
        Objects.requireNonNull(status, "status must not be null");
    }

    @Override
    public String toString() {
        return "ReportLineSnapshot[status=" + status + "]";
    }
}
