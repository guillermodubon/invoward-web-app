package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierCurrencyAmounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKeyAggregate;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListItem;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListQuery;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierFinancialQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierListQueryRepository;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Composes one bounded, owner-scoped supplier group page with batched financial projections. */
public final class ListSuppliersService {

    private static final Comparator<CurrencyAmount> CURRENCY_ORDER =
            Comparator.comparing(CurrencyAmount::currency);

    private final InsightsSupplierListQueryRepository listQueryRepository;
    private final InsightsSupplierFinancialQueryRepository financialQueryRepository;
    private final InsightsMetricCalculator metricCalculator;

    public ListSuppliersService(
            InsightsSupplierListQueryRepository listQueryRepository,
            InsightsSupplierFinancialQueryRepository financialQueryRepository,
            InsightsMetricCalculator metricCalculator) {
        this.listQueryRepository = Objects.requireNonNull(
                listQueryRepository, "listQueryRepository is required");
        this.financialQueryRepository = Objects.requireNonNull(
                financialQueryRepository, "financialQueryRepository is required");
        this.metricCalculator = Objects.requireNonNull(metricCalculator, "metricCalculator is required");
    }

    /**
     * Lists only the requested page for an already-authenticated registered owner. The query
     * models enforce page, size, sort and search bounds before any repository access.
     */
    public SupplierListPage list(UUID registeredUserId, SupplierListQuery query) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(query, "query is required");

        InsightsPage<SupplierKeyAggregate> groupPage = Objects.requireNonNull(
                listQueryRepository.pageSupplierKeys(registeredUserId, query),
                "listQueryRepository must return a supplier page");
        if (groupPage.content().isEmpty()) {
            return new SupplierListPage(new InsightsPage<>(
                    List.of(), groupPage.page(), groupPage.size(), groupPage.totalElements()));
        }

        List<SupplierKey> supplierKeys = groupPage.content().stream()
                .map(SupplierKeyAggregate::supplierKey)
                .toList();
        Set<SupplierKey> requestedKeys = new HashSet<>(supplierKeys);
        Map<SupplierKey, List<CurrencyAmount>> totals = currencyBreakdowns(
                financialQueryRepository.fetchSupplierCurrencyAmounts(registeredUserId, supplierKeys),
                requestedKeys,
                "total invoiced");
        Map<SupplierKey, List<CurrencyAmount>> potentialDifferences = currencyBreakdowns(
                financialQueryRepository.fetchSupplierPotentialDifferencesByCurrency(
                        registeredUserId, supplierKeys),
                requestedKeys,
                "potential differences");

        List<SupplierListItem> items = groupPage.content().stream()
                .map(group -> new SupplierListItem(
                        group.supplierKey(),
                        group.supplierName(),
                        group.completedAnalyses(),
                        group.analysesWithDifferences(),
                        metricCalculator.percentage(
                                group.analysesWithDifferences(), group.completedAnalyses()),
                        totals.getOrDefault(group.supplierKey(), List.of()),
                        potentialDifferences.getOrDefault(group.supplierKey(), List.of())))
                .toList();

        return new SupplierListPage(new InsightsPage<>(
                items, groupPage.page(), groupPage.size(), groupPage.totalElements()));
    }

    private static Map<SupplierKey, List<CurrencyAmount>> currencyBreakdowns(
            List<SupplierCurrencyAmounts> results, Set<SupplierKey> requestedKeys, String label) {
        Objects.requireNonNull(results, "financial query must return a result list");
        Map<SupplierKey, List<CurrencyAmount>> breakdowns = new HashMap<>();
        for (SupplierCurrencyAmounts result : results) {
            if (!requestedKeys.contains(result.supplierKey())) {
                throw new IllegalStateException("Supplier " + label + " query returned a key outside the page.");
            }
            List<CurrencyAmount> sortedAmounts = result.amounts().stream()
                    .sorted(CURRENCY_ORDER)
                    .toList();
            if (breakdowns.putIfAbsent(result.supplierKey(), sortedAmounts) != null) {
                throw new IllegalStateException("Supplier " + label + " query returned a duplicate key.");
            }
        }
        return Map.copyOf(breakdowns);
    }
}
