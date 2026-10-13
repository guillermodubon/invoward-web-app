package io.github.guillermodubon.invoward.reconciliation.domain;

import java.math.BigDecimal;

/** Formats numeric discrepancy values without exponent notation or insignificant zeroes. */
public final class CanonicalValueFormatter {

    public String format(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
