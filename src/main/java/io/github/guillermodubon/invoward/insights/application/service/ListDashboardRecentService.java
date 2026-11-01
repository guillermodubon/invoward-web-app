package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentPage;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentQuery;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.port.InsightsRecentAnalysisQueryRepository;

import java.util.Objects;
import java.util.UUID;

/** Lists a bounded recent-analysis page for one authenticated registered account. */
public final class ListDashboardRecentService {

    private final InsightsRecentAnalysisQueryRepository queryRepository;

    public ListDashboardRecentService(InsightsRecentAnalysisQueryRepository queryRepository) {
        this.queryRepository = Objects.requireNonNull(queryRepository, "queryRepository is required");
    }

    /**
     * Queries only the supplied registered owner. Guest identities are intentionally not accepted
     * by this use case; the caller resolves the authenticated account before invoking it.
     */
    public DashboardRecentPage list(UUID registeredUserId, DashboardRecentQuery query) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(query, "query is required");

        InsightsPage<AnalysisHistoryItem> result = Objects.requireNonNull(
                queryRepository.pageRecent(registeredUserId, query),
                "queryRepository must return a recent-analysis page");
        return new DashboardRecentPage(query.view(), result);
    }
}
