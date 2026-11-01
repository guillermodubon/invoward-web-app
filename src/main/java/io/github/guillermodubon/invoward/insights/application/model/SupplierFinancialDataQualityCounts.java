package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Objects;

/** Missing financial inputs for one owner-scoped, completed supplier group. */
public record SupplierFinancialDataQualityCounts(
        SupplierKey supplierKey,
        long differenceAmountUnavailableCount,
        long missingCurrencyCount) {

    public SupplierFinancialDataQualityCounts {
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        if (differenceAmountUnavailableCount < 0 || missingCurrencyCount < 0) {
            throw new IllegalArgumentException("Supplier data-quality counts cannot be negative.");
        }
    }
}
