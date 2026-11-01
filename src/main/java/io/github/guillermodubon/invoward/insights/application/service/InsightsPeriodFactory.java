package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.insights.application.model.InsightsPeriod;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Creates validated UTC date windows for Insights queries. */
public final class InsightsPeriodFactory {

    private final Clock clock;

    public InsightsPeriodFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** Uses the current UTC calendar month when both dates are absent. */
    public InsightsPeriod create(LocalDate fromInclusive, LocalDate toInclusive) {
        if (fromInclusive == null && toInclusive == null) {
            return currentUtcMonth();
        }
        if (fromInclusive == null || toInclusive == null) {
            throw new IllegalArgumentException("Both period dates are required.");
        }
        if (toInclusive.isBefore(fromInclusive)) {
            throw new IllegalArgumentException("Insights period dates are invalid.");
        }
        long daysBeforeExclusiveEnd = ChronoUnit.DAYS.between(fromInclusive, toInclusive);
        if (daysBeforeExclusiveEnd >= InsightsPeriod.MAX_DAYS) {
            throw new IllegalArgumentException("Insights period exceeds the maximum duration.");
        }
        try {
            return new InsightsPeriod(fromInclusive, toInclusive.plusDays(1));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Insights period dates are invalid.");
        }
    }

    public InsightsPeriod currentUtcMonth() {
        LocalDate currentUtcDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate fromInclusive = currentUtcDate.withDayOfMonth(1);
        try {
            return new InsightsPeriod(fromInclusive, fromInclusive.plusMonths(1));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Current UTC month is outside the supported date range.");
        }
    }
}
