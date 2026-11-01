package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Arrays;

public enum DashboardRecentView {
    RECENT,
    NEEDS_ATTENTION;

    public static DashboardRecentView parse(String value) {
        if (value == null) {
            return RECENT;
        }
        String candidate = value.strip();
        return Arrays.stream(values())
                .filter(view -> view.name().equals(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dashboard view is invalid."));
    }
}
