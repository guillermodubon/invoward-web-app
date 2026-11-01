package io.github.guillermodubon.invoward.insights.api.model;

import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DashboardSummary;
import io.github.guillermodubon.invoward.insights.application.model.DashboardTopSupplier;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPeriod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Explicit HTTP projection for account-scoped dashboard summary data. */
public record DashboardSummaryResponse(
        Period period,
        long completedAnalyses,
        long matchedAnalyses,
        long analysesWithDifferences,
        BigDecimal matchedPercent,
        long needsAttentionCount,
        long failedCount,
        String failedCountBasis,
        List<CurrencyAmountItem> totalInvoicedByCurrency,
        List<CurrencyAmountItem> potentialDifferencesByCurrency,
        String potentialDifferencesDisclaimer,
        long differenceAmountUnavailableCount,
        long missingCurrencyCount,
        long unassignedSupplierCount,
        List<TopSupplierItem> topSuppliers,
        List<DiscrepancyTypeItem> commonDiscrepancyTypes) {

    public DashboardSummaryResponse {
        totalInvoicedByCurrency = List.copyOf(totalInvoicedByCurrency);
        potentialDifferencesByCurrency = List.copyOf(potentialDifferencesByCurrency);
        topSuppliers = List.copyOf(topSuppliers);
        commonDiscrepancyTypes = List.copyOf(commonDiscrepancyTypes);
    }

    public static DashboardSummaryResponse from(DashboardSummary summary) {
        return new DashboardSummaryResponse(
                Period.from(summary.period()),
                summary.counts().completedAnalyses(),
                summary.counts().matchedAnalyses(),
                summary.counts().analysesWithDifferences(),
                summary.matchedPercent(),
                summary.counts().needsAttentionCount(),
                summary.failedCount(),
                "CREATED_AT",
                summary.totalInvoicedByCurrency().stream().map(CurrencyAmountItem::from).toList(),
                summary.potentialDifferencesByCurrency().stream().map(CurrencyAmountItem::from).toList(),
                "Potential differences are absolute invoice-to-reference total variations for analyses requiring "
                        + "review; they are not proven overcharges, recoverable amounts, or savings.",
                summary.dataQualityCounts().differenceAmountUnavailableCount(),
                summary.dataQualityCounts().missingCurrencyCount(),
                summary.dataQualityCounts().unassignedSupplierCount(),
                summary.topSuppliers().stream().map(TopSupplierItem::from).toList(),
                summary.commonDiscrepancyTypes().stream().map(DiscrepancyTypeItem::from).toList());
    }

    public record Period(LocalDate from, LocalDate to, String timezone, String basis) {

        private static Period from(InsightsPeriod period) {
            return new Period(period.fromInclusive(), period.toExclusive(), "UTC", "COMPLETED_AT");
        }
    }

    public record CurrencyAmountItem(String currency, BigDecimal amount, long contributingAnalyses) {

        private static CurrencyAmountItem from(CurrencyAmount amount) {
            return new CurrencyAmountItem(amount.currency(), amount.amount(), amount.contributingAnalyses());
        }
    }

    public record TopSupplierItem(String supplierKey, String supplierName, long completedAnalyses) {

        private static TopSupplierItem from(DashboardTopSupplier supplier) {
            return new TopSupplierItem(
                    supplier.supplierKey().value(), supplier.supplierName(), supplier.completedAnalyses());
        }
    }

    public record DiscrepancyTypeItem(String type, long count) {

        private static DiscrepancyTypeItem from(DiscrepancyTypeFrequency frequency) {
            return new DiscrepancyTypeItem(frequency.type().name(), frequency.count());
        }
    }
}
