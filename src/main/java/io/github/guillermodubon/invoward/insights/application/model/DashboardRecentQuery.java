package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Objects;

public record DashboardRecentQuery(DashboardRecentView view, InsightsPageRequest pagination) {

    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 50;

    public DashboardRecentQuery {
        Objects.requireNonNull(view, "view is required");
        Objects.requireNonNull(pagination, "pagination is required");
        if (pagination.size() > MAX_SIZE) {
            throw new IllegalArgumentException("Dashboard page size is outside the supported range.");
        }
    }

    public static DashboardRecentQuery of(String view, Integer page, Integer size) {
        return new DashboardRecentQuery(
                DashboardRecentView.parse(view),
                InsightsPageRequest.of(page, size, DEFAULT_SIZE));
    }

    public static DashboardRecentQuery defaults() {
        return of(null, null, null);
    }
}
