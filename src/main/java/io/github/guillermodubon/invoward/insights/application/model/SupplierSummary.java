package io.github.guillermodubon.invoward.insights.application.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record SupplierSummary(
        SupplierKey supplierKey,
        String supplierName,
        SupplierAggregateCounts counts,
        BigDecimal differenceRatePercent,
        List<CurrencyAmount> totalInvoicedByCurrency,
        List<CurrencyAmount> potentialDifferencesByCurrency,
        List<DiscrepancyTypeFrequency> commonDiscrepancyTypes,
        List<AnalysisHistoryItem> recentAnalyses) {

    public SupplierSummary {
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        Objects.requireNonNull(supplierName, "supplierName is required");
        Objects.requireNonNull(counts, "counts are required");
        if (supplierName.isBlank()) {
            throw new IllegalArgumentException("Supplier name is required.");
        }
        InsightsModelValidation.validatePercent(differenceRatePercent, counts.completedAnalyses());
        totalInvoicedByCurrency = InsightsModelValidation.currencyList(
                totalInvoicedByCurrency, "totalInvoicedByCurrency");
        potentialDifferencesByCurrency = InsightsModelValidation.currencyList(
                potentialDifferencesByCurrency, "potentialDifferencesByCurrency");
        commonDiscrepancyTypes = InsightsModelValidation.immutableList(
                commonDiscrepancyTypes, "commonDiscrepancyTypes", 5);
        recentAnalyses = InsightsModelValidation.immutableList(recentAnalyses, "recentAnalyses", 5);
    }
}
