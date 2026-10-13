package io.github.guillermodubon.invoward.reconciliation.domain;

import java.math.BigDecimal;

/** Validates exact values representable by PostgreSQL NUMERIC(19,4) without rounding them. */
public final class Numeric19_4 {

    private static final int SCALE = 4;
    private static final int MAX_INTEGER_DIGITS = 15;

    private Numeric19_4() {
    }

    public static void requireCompatible(BigDecimal value, String field) {
        if (value == null || value.signum() == 0) {
            return;
        }

        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() > SCALE) {
            throw new IllegalArgumentException(field + " exceeds NUMERIC(19,4) scale");
        }
        long integerDigits = Math.max(0L, (long) normalized.precision() - normalized.scale());
        if (integerDigits > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException(field + " exceeds NUMERIC(19,4) precision");
        }
    }
}
