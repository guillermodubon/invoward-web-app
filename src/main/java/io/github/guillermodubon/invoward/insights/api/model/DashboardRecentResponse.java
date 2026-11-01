package io.github.guillermodubon.invoward.insights.api.model;

import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentPage;

import java.util.List;

/** Stable HTTP page response for the dashboard recent-analysis views. */
public record DashboardRecentResponse(
        String view,
        List<DashboardRecentAnalysisItem> content,
        int page,
        int size,
        long totalElements,
        long totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    public DashboardRecentResponse {
        content = List.copyOf(content);
    }

    public static DashboardRecentResponse from(DashboardRecentPage recentPage) {
        var page = recentPage.page();
        return new DashboardRecentResponse(
                recentPage.view().name(),
                page.content().stream().map(DashboardRecentAnalysisItem::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.hasNext(),
                page.hasPrevious());
    }
}
