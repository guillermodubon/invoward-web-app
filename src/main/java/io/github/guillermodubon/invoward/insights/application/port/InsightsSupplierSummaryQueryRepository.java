package io.github.guillermodubon.invoward.insights.application.port;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Owner-scoped read operations used to assemble one supplier summary. */
public interface InsightsSupplierSummaryQueryRepository {

    /** Empty means the exact key has no eligible completed Analysis for this registered owner. */
    Optional<String> findOwnedCompletedSupplierDisplayName(UUID registeredUserId, SupplierKey supplierKey);

    SupplierAggregateCounts fetchSupplierCompletedCounts(UUID registeredUserId, SupplierKey supplierKey);

    /** Counts persisted discrepancy rows, regardless of their current resolution status. */
    List<DiscrepancyTypeFrequency> fetchSupplierCommonIssues(
            UUID registeredUserId,
            SupplierKey supplierKey,
            int limit);

    /** Returns at most the requested completed rows ordered by completedAt DESC/id DESC. */
    List<AnalysisHistoryItem> fetchSupplierRecent(
            UUID registeredUserId,
            SupplierKey supplierKey,
            int limit);
}
