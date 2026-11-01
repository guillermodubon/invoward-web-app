package io.github.guillermodubon.invoward.insights.application.port;

import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DashboardAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardDataQualityCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardTopSupplier;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPeriod;

import java.util.List;
import java.util.UUID;

/** Query boundary; every operation must apply registeredUserId in PostgreSQL. */
public interface InsightsDashboardQueryRepository {

    DashboardAggregateCounts fetchCompletedCounts(UUID registeredUserId, InsightsPeriod completedPeriod);

    /** Failed analyses use created_at for the supplied UTC calendar window. */
    long fetchFailedCountByCreationPeriod(UUID registeredUserId, InsightsPeriod creationPeriod);

    List<CurrencyAmount> fetchTotalsByCurrency(UUID registeredUserId, InsightsPeriod completedPeriod);

    List<CurrencyAmount> fetchPotentialDifferencesByCurrency(UUID registeredUserId, InsightsPeriod completedPeriod);

    /** Bounded top-N group query with supplierKey ASC as the deterministic tie-break. */
    List<DashboardTopSupplier> fetchTopSuppliers(UUID registeredUserId, InsightsPeriod completedPeriod, int limit);

    /** Counts discrepancy rows by type, limited and ordered count DESC/type ASC. */
    List<DiscrepancyTypeFrequency> fetchCommonDiscrepancyTypes(
            UUID registeredUserId,
            InsightsPeriod completedPeriod,
            int limit);

    DashboardDataQualityCounts fetchMissingDataCounts(UUID registeredUserId, InsightsPeriod completedPeriod);
}
