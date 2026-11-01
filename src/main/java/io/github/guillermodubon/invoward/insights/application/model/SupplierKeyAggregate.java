package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Objects;

/** One owner-scoped grouped supplier row before currency totals are attached. */
public record SupplierKeyAggregate(
        SupplierKey supplierKey,
        String supplierName,
        long completedAnalyses,
        long analysesWithDifferences) {

    public SupplierKeyAggregate {
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        Objects.requireNonNull(supplierName, "supplierName is required");
        if (supplierName.isBlank()
                || completedAnalyses < 1
                || analysesWithDifferences < 0
                || analysesWithDifferences > completedAnalyses) {
            throw new IllegalArgumentException("Supplier aggregate is inconsistent.");
        }
    }
}
