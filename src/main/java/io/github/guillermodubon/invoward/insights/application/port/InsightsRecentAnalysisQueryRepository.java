package io.github.guillermodubon.invoward.insights.application.port;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentQuery;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;

import java.util.UUID;

/** Owner-scoped, database-paginated read boundary for dashboard recent analyses. */
public interface InsightsRecentAnalysisQueryRepository {

    InsightsPage<AnalysisHistoryItem> pageRecent(UUID registeredUserId, DashboardRecentQuery query);
}
