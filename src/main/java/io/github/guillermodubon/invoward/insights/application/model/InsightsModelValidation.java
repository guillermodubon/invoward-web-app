package io.github.guillermodubon.invoward.insights.application.model;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class InsightsModelValidation {

    private InsightsModelValidation() {
    }

    static void validatePercent(BigDecimal value, long denominator) {
        if (denominator == 0) {
            if (value != null) {
                throw new IllegalArgumentException("A percentage requires a nonzero denominator.");
            }
            return;
        }
        if (value == null
                || value.scale() != 2
                || value.signum() < 0
                || value.compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException("Percentage is inconsistent with its denominator.");
        }
    }

    static List<CurrencyAmount> currencyList(List<CurrencyAmount> values, String field) {
        Objects.requireNonNull(values, field + " is required");
        List<CurrencyAmount> immutable = List.copyOf(values);
        Set<String> currencies = new HashSet<>();
        if (immutable.stream().anyMatch(amount -> !currencies.add(amount.currency()))) {
            throw new IllegalArgumentException(field + " contains duplicate currencies.");
        }
        return immutable;
    }

    static <T> List<T> immutableList(List<T> values, String field, int maximumSize) {
        Objects.requireNonNull(values, field + " is required");
        List<T> immutable = List.copyOf(values);
        if (immutable.size() > maximumSize) {
            throw new IllegalArgumentException(field + " exceeds the supported result limit.");
        }
        return immutable;
    }
}
