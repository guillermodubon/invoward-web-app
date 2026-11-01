package io.github.guillermodubon.invoward.insights.application.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;

import java.util.Objects;

/** Bounded safe page of existing Analysis history projections. */
public record DashboardRecentPage(DashboardRecentView view, InsightsPage<AnalysisHistoryItem> page) {

    public DashboardRecentPage {
        Objects.requireNonNull(view, "view is required");
        Objects.requireNonNull(page, "page is required");
        if (page.size() > DashboardRecentQuery.MAX_SIZE) {
            throw new IllegalArgumentException("Dashboard page size is outside the supported range.");
        }
    }
}
