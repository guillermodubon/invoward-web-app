package io.github.guillermodubon.invoward.insights.application.model;

/** Owner-scoped count projection; matched and review-required outcomes partition completed rows. */
public record DashboardAggregateCounts(
        long completedAnalyses,
        long matchedAnalyses,
        long analysesWithDifferences,
        long needsAttentionCount) {

    public DashboardAggregateCounts {
        requireNonnegative(completedAnalyses, "completedAnalyses");
        requireNonnegative(matchedAnalyses, "matchedAnalyses");
        requireNonnegative(analysesWithDifferences, "analysesWithDifferences");
        requireNonnegative(needsAttentionCount, "needsAttentionCount");
        if (matchedAnalyses > completedAnalyses
                || analysesWithDifferences != completedAnalyses - matchedAnalyses
                || needsAttentionCount > analysesWithDifferences) {
            throw new IllegalArgumentException("Dashboard counts are inconsistent.");
        }
    }

    private static void requireNonnegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative.");
        }
    }
}
