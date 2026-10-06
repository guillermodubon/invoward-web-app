package io.github.guillermodubon.invoward.extraction.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Numeric19_4 {

    private static final int PRECISION = 19;
    private static final int SCALE = 4;

    private Numeric19_4() {
    }

    static BigDecimal requireNonNegative(BigDecimal value, String field) {
        return requireCompatible(value, field, false);
    }

    static BigDecimal requirePositive(BigDecimal value, String field) {
        return requireCompatible(value, field, true);
    }

    private static BigDecimal requireCompatible(BigDecimal value, String field, boolean positive) {
        if (value == null) {
            return null;
        }
        if (positive ? value.signum() <= 0 : value.signum() < 0) {
            throw new IllegalArgumentException(field + (positive ? " must be greater than 0" : " must not be negative"));
        }
        if (value.signum() == 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }

        try {
            BigDecimal exact = value;
            if (value.scale() < SCALE && (long) value.precision() + SCALE - value.scale() > PRECISION) {
                throw new IllegalArgumentException(field + " exceeds NUMERIC(19,4) precision");
            }
            if (value.scale() > SCALE) {
                if ((long) value.scale() - SCALE > value.precision()) {
                    throw new IllegalArgumentException(field + " exceeds NUMERIC(19,4) scale");
                }
                exact = value.stripTrailingZeros();
                if (exact.scale() > SCALE) {
                    throw new IllegalArgumentException(field + " exceeds NUMERIC(19,4) scale");
                }
            }
            BigDecimal normalized = exact.setScale(SCALE, RoundingMode.UNNECESSARY);
            if (normalized.precision() > PRECISION) {
                throw new IllegalArgumentException(field + " exceeds NUMERIC(19,4) precision");
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " exceeds NUMERIC(19,4) scale", exception);
        }
    }
}
