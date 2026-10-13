package io.github.guillermodubon.invoward.reconciliation.application.model;

/** Stable counts for persisted reconciliation lines and discrepancy lifecycle states. */
public record ReconciliationSummary(
        long lineCount,
        long matchedCount,
        long differentCount,
        long missingCount,
        long unexpectedCount,
        long openDiscrepancyCount,
        long acceptedDiscrepancyCount,
        long resolvedDiscrepancyCount) {

    public ReconciliationSummary {
        requireNonnegative(lineCount, "lineCount");
        requireNonnegative(matchedCount, "matchedCount");
        requireNonnegative(differentCount, "differentCount");
        requireNonnegative(missingCount, "missingCount");
        requireNonnegative(unexpectedCount, "unexpectedCount");
        requireNonnegative(openDiscrepancyCount, "openDiscrepancyCount");
        requireNonnegative(acceptedDiscrepancyCount, "acceptedDiscrepancyCount");
        requireNonnegative(resolvedDiscrepancyCount, "resolvedDiscrepancyCount");
        long representedLines = Math.addExact(Math.addExact(matchedCount, differentCount),
                Math.addExact(missingCount, unexpectedCount));
        if (representedLines != lineCount) {
            throw new IllegalArgumentException("line status counts must sum to lineCount");
        }
    }

    private static void requireNonnegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
