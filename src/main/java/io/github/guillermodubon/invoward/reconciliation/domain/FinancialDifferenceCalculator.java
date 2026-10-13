package io.github.guillermodubon.invoward.reconciliation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calculates deterministic signed, absolute, and reference-relative financial differences. */
public final class FinancialDifferenceCalculator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int PERCENTAGE_SCALE = 6;

    public FinancialDifference calculate(BigDecimal reference, BigDecimal invoice) {
        if (reference == null || invoice == null) {
            return new FinancialDifference(null, null, null);
        }

        BigDecimal signedDifference = invoice.subtract(reference);
        BigDecimal absoluteDifference = signedDifference.abs();
        BigDecimal percentageDifference = reference.signum() == 0
                ? null
                : absoluteDifference.multiply(ONE_HUNDRED)
                        .divide(reference.abs(), PERCENTAGE_SCALE, RoundingMode.HALF_UP);
        return new FinancialDifference(signedDifference, absoluteDifference, percentageDifference);
    }
}
