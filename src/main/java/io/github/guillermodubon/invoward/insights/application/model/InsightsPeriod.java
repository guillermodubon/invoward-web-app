package io.github.guillermodubon.invoward.insights.application.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Half-open UTC calendar-date interval used by Insights queries. */
public record InsightsPeriod(LocalDate fromInclusive, LocalDate toExclusive) {

    public static final long MAX_DAYS = 366;

    public InsightsPeriod {
        Objects.requireNonNull(fromInclusive, "fromInclusive is required");
        Objects.requireNonNull(toExclusive, "toExclusive is required");
        if (!toExclusive.isAfter(fromInclusive)) {
            throw new IllegalArgumentException("Insights period must have a positive duration.");
        }
        if (ChronoUnit.DAYS.between(fromInclusive, toExclusive) > MAX_DAYS) {
            throw new IllegalArgumentException("Insights period exceeds the maximum duration.");
        }
    }
}
