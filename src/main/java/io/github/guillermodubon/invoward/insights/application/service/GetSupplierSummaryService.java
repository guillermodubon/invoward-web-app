package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.insights.application.exception.SupplierNotFoundException;
import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierCurrencyAmounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierDisplayNameSelector;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.model.SupplierSummary;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierFinancialQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierSummaryQueryRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Assembles persisted supplier insights for one registered owner without triggering processing. */
public final class GetSupplierSummaryService {

    private static final int RESULT_LIMIT = 5;

    private static final Comparator<CurrencyAmount> CURRENCY_ORDER =
            Comparator.comparing(CurrencyAmount::currency);

    private static final Comparator<DiscrepancyTypeFrequency> ISSUE_ORDER =
            Comparator.comparingLong(DiscrepancyTypeFrequency::count)
                    .reversed()
                    .thenComparing(issue -> issue.type().name());

    private static final Comparator<AnalysisHistoryItem> RECENT_ORDER =
            Comparator.comparing(
                            AnalysisHistoryItem::completedAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(AnalysisHistoryItem::id, Comparator.reverseOrder());

    private final InsightsSupplierSummaryQueryRepository summaryQueryRepository;
    private final InsightsSupplierFinancialQueryRepository financialQueryRepository;
    private final InsightsMetricCalculator metricCalculator;

    public GetSupplierSummaryService(
            InsightsSupplierSummaryQueryRepository summaryQueryRepository,
            InsightsSupplierFinancialQueryRepository financialQueryRepository,
            InsightsMetricCalculator metricCalculator) {
        this.summaryQueryRepository = Objects.requireNonNull(
                summaryQueryRepository, "summaryQueryRepository is required");
        this.financialQueryRepository = Objects.requireNonNull(
                financialQueryRepository, "financialQueryRepository is required");
        this.metricCalculator = Objects.requireNonNull(metricCalculator, "metricCalculator is required");
    }

    /**
     * Resolves an exact supplier key for the registered owner before any other query. Unknown and
     * foreign keys deliberately produce the same application exception.
     */
    public SupplierSummary get(UUID registeredUserId, SupplierKey supplierKey) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(supplierKey, "supplierKey is required");

        String persistedDisplayName = Objects.requireNonNull(
                        summaryQueryRepository.findOwnedCompletedSupplierDisplayName(
                                registeredUserId, supplierKey),
                        "summary query must return a supplier-existence result")
                .orElseThrow(SupplierNotFoundException::new);
        String supplierName = SupplierDisplayNameSelector.select(supplierKey, persistedDisplayName);

        SupplierAggregateCounts counts = Objects.requireNonNull(
                summaryQueryRepository.fetchSupplierCompletedCounts(registeredUserId, supplierKey),
                "summary query must return supplier counts");
        List<SupplierKey> singletonKey = List.of(supplierKey);
        List<CurrencyAmount> totalInvoiced = currencyBreakdown(
                financialQueryRepository.fetchSupplierCurrencyAmounts(registeredUserId, singletonKey),
                supplierKey,
                "total invoiced");
        List<CurrencyAmount> potentialDifferences = currencyBreakdown(
                financialQueryRepository.fetchSupplierPotentialDifferencesByCurrency(
                        registeredUserId, singletonKey),
                supplierKey,
                "potential differences");
        List<DiscrepancyTypeFrequency> commonIssues = Objects.requireNonNull(
                        summaryQueryRepository.fetchSupplierCommonIssues(
                                registeredUserId, supplierKey, RESULT_LIMIT),
                        "summary query must return discrepancy frequencies")
                .stream()
                .sorted(ISSUE_ORDER)
                .toList();
        List<AnalysisHistoryItem> recent = Objects.requireNonNull(
                        summaryQueryRepository.fetchSupplierRecent(
                                registeredUserId, supplierKey, RESULT_LIMIT),
                        "summary query must return recent analyses")
                .stream()
                .sorted(RECENT_ORDER)
                .toList();

        return new SupplierSummary(
                supplierKey,
                supplierName,
                counts,
                metricCalculator.percentage(counts.analysesWithDifferences(), counts.completedAnalyses()),
                totalInvoiced,
                potentialDifferences,
                commonIssues,
                recent);
    }

    private static List<CurrencyAmount> currencyBreakdown(
            List<SupplierCurrencyAmounts> groups, SupplierKey supplierKey, String label) {
        Objects.requireNonNull(groups, "financial query must return a result list");
        SupplierCurrencyAmounts matchingGroup = null;
        for (SupplierCurrencyAmounts group : groups) {
            if (!supplierKey.equals(group.supplierKey())) {
                throw new IllegalStateException("Supplier " + label + " query returned an unrelated key.");
            }
            if (matchingGroup != null) {
                throw new IllegalStateException("Supplier " + label + " query returned a duplicate key.");
            }
            matchingGroup = group;
        }
        if (matchingGroup == null) {
            return List.of();
        }
        return matchingGroup.amounts().stream()
                .sorted(CURRENCY_ORDER)
                .toList();
    }
}
