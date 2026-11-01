package io.github.guillermodubon.invoward.insights.application.model;

/** Owner-scoped aggregates for a supplier with at least one eligible completed Analysis. */
public record SupplierAggregateCounts(
        long completedAnalyses,
        long matchedAnalyses,
        long analysesWithDifferences,
        long differenceAmountUnavailableCount,
        long missingCurrencyCount) {

    public SupplierAggregateCounts {
        if (completedAnalyses < 1
                || matchedAnalyses < 0
                || analysesWithDifferences < 0
                || differenceAmountUnavailableCount < 0
                || missingCurrencyCount < 0
                || matchedAnalyses > completedAnalyses
                || analysesWithDifferences != completedAnalyses - matchedAnalyses
                || differenceAmountUnavailableCount > analysesWithDifferences
                || missingCurrencyCount > completedAnalyses) {
            throw new IllegalArgumentException("Supplier counts are inconsistent.");
        }
    }
}
