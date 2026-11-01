package io.github.guillermodubon.invoward.insights.application.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record SupplierListItem(
        SupplierKey supplierKey,
        String supplierName,
        long completedAnalyses,
        long analysesWithDifferences,
        BigDecimal differenceRatePercent,
        List<CurrencyAmount> totalInvoicedByCurrency,
        List<CurrencyAmount> potentialDifferencesByCurrency) {

    public SupplierListItem {
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        Objects.requireNonNull(supplierName, "supplierName is required");
        if (supplierName.isBlank()
                || completedAnalyses < 1
                || analysesWithDifferences < 0
                || analysesWithDifferences > completedAnalyses) {
            throw new IllegalArgumentException("Supplier list item is inconsistent.");
        }
        InsightsModelValidation.validatePercent(differenceRatePercent, completedAnalyses);
        totalInvoicedByCurrency = InsightsModelValidation.currencyList(
                totalInvoicedByCurrency, "totalInvoicedByCurrency");
        potentialDifferencesByCurrency = InsightsModelValidation.currencyList(
                potentialDifferencesByCurrency, "potentialDifferencesByCurrency");
    }
}
