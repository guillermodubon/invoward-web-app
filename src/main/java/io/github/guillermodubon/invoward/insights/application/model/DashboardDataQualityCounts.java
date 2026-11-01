package io.github.guillermodubon.invoward.insights.application.model;

public record DashboardDataQualityCounts(
        long differenceAmountUnavailableCount,
        long missingCurrencyCount,
        long unassignedSupplierCount) {

    public DashboardDataQualityCounts {
        if (differenceAmountUnavailableCount < 0 || missingCurrencyCount < 0 || unassignedSupplierCount < 0) {
            throw new IllegalArgumentException("Dashboard data-quality counts cannot be negative.");
        }
    }
}
