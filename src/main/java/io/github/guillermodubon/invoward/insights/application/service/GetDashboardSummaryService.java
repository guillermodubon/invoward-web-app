package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DashboardAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardDataQualityCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardSummary;
import io.github.guillermodubon.invoward.insights.application.model.DashboardTopSupplier;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPeriod;
import io.github.guillermodubon.invoward.insights.application.port.InsightsDashboardQueryRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Composes persisted, owner-scoped dashboard aggregates into a read-only summary. */
public final class GetDashboardSummaryService {

    private static final int TOP_RESULT_LIMIT = 5;

    private static final Comparator<DashboardTopSupplier> TOP_SUPPLIER_ORDER =
            Comparator.comparingLong(DashboardTopSupplier::completedAnalyses)
                    .reversed()
                    .thenComparing(supplier -> supplier.supplierKey().value());

    private static final Comparator<DiscrepancyTypeFrequency> DISCREPANCY_ORDER =
            Comparator.comparingLong(DiscrepancyTypeFrequency::count)
                    .reversed()
                    .thenComparing(frequency -> frequency.type().name());

    private final InsightsDashboardQueryRepository queryRepository;
    private final InsightsPeriodFactory periodFactory;
    private final InsightsMetricCalculator metricCalculator;

    public GetDashboardSummaryService(
            InsightsDashboardQueryRepository queryRepository,
            InsightsPeriodFactory periodFactory,
            InsightsMetricCalculator metricCalculator) {
        this.queryRepository = Objects.requireNonNull(queryRepository, "queryRepository is required");
        this.periodFactory = Objects.requireNonNull(periodFactory, "periodFactory is required");
        this.metricCalculator = Objects.requireNonNull(metricCalculator, "metricCalculator is required");
    }

    /**
     * Builds a summary for an already-authorized registered account. When both dates are absent,
     * the current UTC calendar month is used; custom dates are inclusive at the API boundary.
     */
    public DashboardSummary get(UUID registeredUserId, LocalDate fromInclusive, LocalDate toInclusive) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        InsightsPeriod period = periodFactory.create(fromInclusive, toInclusive);

        DashboardAggregateCounts counts = Objects.requireNonNull(
                queryRepository.fetchCompletedCounts(registeredUserId, period),
                "queryRepository must return completed counts");
        long failedCount = queryRepository.fetchFailedCountByCreationPeriod(registeredUserId, period);
        List<CurrencyAmount> totals = Objects.requireNonNull(
                queryRepository.fetchTotalsByCurrency(registeredUserId, period),
                "queryRepository must return currency totals");
        List<CurrencyAmount> potentialDifferences = Objects.requireNonNull(
                queryRepository.fetchPotentialDifferencesByCurrency(registeredUserId, period),
                "queryRepository must return currency differences");
        List<DashboardTopSupplier> topSuppliers = Objects.requireNonNull(
                queryRepository.fetchTopSuppliers(registeredUserId, period, TOP_RESULT_LIMIT),
                "queryRepository must return top suppliers");
        List<DiscrepancyTypeFrequency> commonDiscrepancyTypes = Objects.requireNonNull(
                queryRepository.fetchCommonDiscrepancyTypes(registeredUserId, period, TOP_RESULT_LIMIT),
                "queryRepository must return discrepancy frequencies");
        DashboardDataQualityCounts dataQualityCounts = Objects.requireNonNull(
                queryRepository.fetchMissingDataCounts(registeredUserId, period),
                "queryRepository must return data quality counts");

        return new DashboardSummary(
                period,
                counts,
                metricCalculator.percentage(counts.matchedAnalyses(), counts.completedAnalyses()),
                failedCount,
                sortCurrencies(totals),
                sortCurrencies(potentialDifferences),
                dataQualityCounts,
                topSuppliers.stream().sorted(TOP_SUPPLIER_ORDER).toList(),
                commonDiscrepancyTypes.stream().sorted(DISCREPANCY_ORDER).toList());
    }

    private static List<CurrencyAmount> sortCurrencies(List<CurrencyAmount> values) {
        return values.stream().sorted(Comparator.comparing(CurrencyAmount::currency)).toList();
    }
}
