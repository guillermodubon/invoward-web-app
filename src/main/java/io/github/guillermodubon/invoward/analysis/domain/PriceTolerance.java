package io.github.guillermodubon.invoward.analysis.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** User-selected comparison tolerance persisted with an Analysis. */
public record PriceTolerance(BigDecimal priceTolerancePercent, BigDecimal priceToleranceAbsolute) {

    private static final List<BigDecimal> ALLOWED_PERCENTAGES = List.of(
            BigDecimal.ZERO,
            BigDecimal.ONE,
            new BigDecimal("3"),
            new BigDecimal("5"));
    private static final int MAX_ABSOLUTE_SCALE = 4;
    private static final int MAX_ABSOLUTE_INTEGER_DIGITS = 15;

    public PriceTolerance {
        Objects.requireNonNull(priceTolerancePercent, "priceTolerancePercent must not be null");
        if (ALLOWED_PERCENTAGES.stream().noneMatch(allowed -> allowed.compareTo(priceTolerancePercent) == 0)) {
            throw new IllegalArgumentException("Price tolerance percent must be one of 0, 1, 3, or 5");
        }
        validateAbsolute(priceToleranceAbsolute);
    }

    public static PriceTolerance exactMatch() {
        return new PriceTolerance(BigDecimal.ZERO, null);
    }

    private static void validateAbsolute(BigDecimal absolute) {
        if (absolute == null) {
            return;
        }
        if (absolute.signum() < 0) {
            throw new IllegalArgumentException("Price tolerance absolute must not be negative");
        }
        if (absolute.scale() > MAX_ABSOLUTE_SCALE) {
            throw new IllegalArgumentException("Price tolerance absolute must have at most 4 decimal places");
        }

        BigDecimal normalized = absolute.abs().stripTrailingZeros();
        int integerDigits = normalized.signum() == 0
                ? 1
                : Math.max(0, normalized.precision() - normalized.scale());
        if (integerDigits > MAX_ABSOLUTE_INTEGER_DIGITS) {
            throw new IllegalArgumentException("Price tolerance absolute exceeds NUMERIC(19,4) precision");
        }
    }
}
