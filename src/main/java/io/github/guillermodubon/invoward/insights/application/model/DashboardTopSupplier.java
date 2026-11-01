package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Objects;

/** Safe, bounded supplier ranking row for a registered account's dashboard. */
public record DashboardTopSupplier(SupplierKey supplierKey, String supplierName, long completedAnalyses) {

    public DashboardTopSupplier {
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        Objects.requireNonNull(supplierName, "supplierName is required");
        if (supplierName.isBlank()) {
            throw new IllegalArgumentException("Supplier name is required.");
        }
        if (completedAnalyses < 1) {
            throw new IllegalArgumentException("A ranked supplier must have completed analyses.");
        }
    }
}
