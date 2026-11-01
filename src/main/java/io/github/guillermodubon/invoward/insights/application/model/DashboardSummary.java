package io.github.guillermodubon.invoward.insights.application.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Safe account-scoped dashboard projection assembled from persisted aggregates. */
public record DashboardSummary(
        InsightsPeriod period,
        DashboardAggregateCounts counts,
        BigDecimal matchedPercent,
        long failedCount,
        List<CurrencyAmount> totalInvoicedByCurrency,
        List<CurrencyAmount> potentialDifferencesByCurrency,
        DashboardDataQualityCounts dataQualityCounts,
        List<DashboardTopSupplier> topSuppliers,
        List<DiscrepancyTypeFrequency> commonDiscrepancyTypes) {

    public DashboardSummary {
        Objects.requireNonNull(period, "period is required");
        Objects.requireNonNull(counts, "counts are required");
        Objects.requireNonNull(dataQualityCounts, "dataQualityCounts are required");
        if (failedCount < 0
                || dataQualityCounts.differenceAmountUnavailableCount() > counts.analysesWithDifferences()
                || dataQualityCounts.missingCurrencyCount() > counts.completedAnalyses()
                || dataQualityCounts.unassignedSupplierCount() > counts.completedAnalyses()) {
            throw new IllegalArgumentException("Dashboard summary counts are inconsistent.");
        }
        InsightsModelValidation.validatePercent(matchedPercent, counts.completedAnalyses());
        totalInvoicedByCurrency = InsightsModelValidation.currencyList(
                totalInvoicedByCurrency, "totalInvoicedByCurrency");
        potentialDifferencesByCurrency = InsightsModelValidation.currencyList(
                potentialDifferencesByCurrency, "potentialDifferencesByCurrency");
        topSuppliers = InsightsModelValidation.immutableList(topSuppliers, "topSuppliers", 5);
        commonDiscrepancyTypes = InsightsModelValidation.immutableList(
                commonDiscrepancyTypes, "commonDiscrepancyTypes", 5);
    }
}
