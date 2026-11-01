package io.github.guillermodubon.invoward.insights.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Deterministic count arithmetic for Insights, with no floating-point operations. */
public final class InsightsMetricCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int PERCENT_SCALE = 2;

    /** Returns null when there are no observations; otherwise returns a 0–100 percentage. */
    public BigDecimal percentage(long numerator, long denominator) {
        requireNonnegative(numerator, "numerator");
        requireNonnegative(denominator, "denominator");
        if (numerator > denominator) {
            throw new IllegalArgumentException("Numerator cannot exceed denominator.");
        }
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(denominator), PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    /** Adds nonnegative counts without allowing silent long overflow. */
    public long sumCounts(long first, long second) {
        requireNonnegative(first, "count");
        requireNonnegative(second, "count");
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Count total exceeds the supported range.");
        }
    }

    private static void requireNonnegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative.");
        }
    }
}
