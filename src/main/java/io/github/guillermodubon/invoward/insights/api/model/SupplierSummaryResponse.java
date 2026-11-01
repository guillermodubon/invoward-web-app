package io.github.guillermodubon.invoward.insights.api.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.SupplierSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Explicit and bounded HTTP projection of one registered owner's supplier summary. */
public record SupplierSummaryResponse(
        String supplierKey,
        String supplierName,
        long completedAnalyses,
        long matchedAnalyses,
        long analysesWithDifferences,
        BigDecimal differenceRatePercent,
        List<CurrencyAmountItem> totalInvoicedByCurrency,
        List<CurrencyAmountItem> potentialDifferencesByCurrency,
        long differenceAmountUnavailableCount,
        long missingCurrencyCount,
        List<DiscrepancyTypeItem> commonDiscrepancyTypes,
        List<RecentAnalysisItem> recentAnalyses) {

    public SupplierSummaryResponse {
        totalInvoicedByCurrency = List.copyOf(totalInvoicedByCurrency);
        potentialDifferencesByCurrency = List.copyOf(potentialDifferencesByCurrency);
        commonDiscrepancyTypes = List.copyOf(commonDiscrepancyTypes);
        recentAnalyses = List.copyOf(recentAnalyses);
    }

    public static SupplierSummaryResponse from(SupplierSummary summary) {
        return new SupplierSummaryResponse(
                summary.supplierKey().value(),
                summary.supplierName(),
                summary.counts().completedAnalyses(),
                summary.counts().matchedAnalyses(),
                summary.counts().analysesWithDifferences(),
                summary.differenceRatePercent(),
                summary.totalInvoicedByCurrency().stream().map(CurrencyAmountItem::from).toList(),
                summary.potentialDifferencesByCurrency().stream().map(CurrencyAmountItem::from).toList(),
                summary.counts().differenceAmountUnavailableCount(),
                summary.counts().missingCurrencyCount(),
                summary.commonDiscrepancyTypes().stream().map(DiscrepancyTypeItem::from).toList(),
                summary.recentAnalyses().stream().map(RecentAnalysisItem::from).toList());
    }

    public record CurrencyAmountItem(String currency, BigDecimal amount, long contributingAnalyses) {

        private static CurrencyAmountItem from(CurrencyAmount amount) {
            return new CurrencyAmountItem(amount.currency(), amount.amount(), amount.contributingAnalyses());
        }
    }

    public record DiscrepancyTypeItem(String type, long count) {

        private static DiscrepancyTypeItem from(DiscrepancyTypeFrequency frequency) {
            return new DiscrepancyTypeItem(frequency.type().name(), frequency.count());
        }
    }

    /** The supplier summary contract intentionally exposes only its compact recent-analysis fields. */
    public record RecentAnalysisItem(
            UUID id,
            String invoiceNumber,
            AnalysisStatus status,
            AnalysisReconciliationStatus reconciliationStatus,
            AnalysisReviewStatus reviewStatus,
            Instant createdAt,
            Instant completedAt) {

        private static RecentAnalysisItem from(AnalysisHistoryItem item) {
            return new RecentAnalysisItem(
                    item.id(),
                    item.invoiceNumber(),
                    item.status(),
                    item.reconciliationStatus(),
                    item.reviewStatus(),
                    item.createdAt(),
                    item.completedAt());
        }
    }
}
