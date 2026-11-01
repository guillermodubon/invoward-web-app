package io.github.guillermodubon.invoward.insights.api.model;

import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListItem;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListPage;

import java.math.BigDecimal;
import java.util.List;

/** Explicit account-scoped supplier-list HTTP projection. */
public record SupplierListResponse(
        List<SupplierItem> content,
        int page,
        int size,
        long totalElements,
        long totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    public SupplierListResponse {
        content = List.copyOf(content);
    }

    public static SupplierListResponse from(SupplierListPage supplierListPage) {
        var page = supplierListPage.page();
        return new SupplierListResponse(
                page.content().stream().map(SupplierItem::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.hasNext(),
                page.hasPrevious());
    }

    public record SupplierItem(
            String supplierKey,
            String supplierName,
            long completedAnalyses,
            long analysesWithDifferences,
            BigDecimal differenceRatePercent,
            List<CurrencyAmountItem> totalInvoicedByCurrency,
            List<CurrencyAmountItem> potentialDifferencesByCurrency) {

        public SupplierItem {
            totalInvoicedByCurrency = List.copyOf(totalInvoicedByCurrency);
            potentialDifferencesByCurrency = List.copyOf(potentialDifferencesByCurrency);
        }

        private static SupplierItem from(SupplierListItem item) {
            return new SupplierItem(
                    item.supplierKey().value(),
                    item.supplierName(),
                    item.completedAnalyses(),
                    item.analysesWithDifferences(),
                    item.differenceRatePercent(),
                    item.totalInvoicedByCurrency().stream().map(CurrencyAmountItem::from).toList(),
                    item.potentialDifferencesByCurrency().stream().map(CurrencyAmountItem::from).toList());
        }
    }

    public record CurrencyAmountItem(String currency, BigDecimal amount, long contributingAnalyses) {

        private static CurrencyAmountItem from(CurrencyAmount amount) {
            return new CurrencyAmountItem(amount.currency(), amount.amount(), amount.contributingAnalyses());
        }
    }
}
