package io.github.guillermodubon.invoward.reconciliation.domain;

import java.math.BigDecimal;

/** Signed and absolute numeric differences, plus the reference-relative percentage when defined. */
public record FinancialDifference(
        BigDecimal signedDifference,
        BigDecimal absoluteDifference,
        BigDecimal percentageDifference) {

    public FinancialDifference {
        if ((signedDifference == null) != (absoluteDifference == null)) {
            throw new IllegalArgumentException("signed and absolute differences must both be present or absent");
        }
        if (signedDifference == null && percentageDifference != null) {
            throw new IllegalArgumentException("percentageDifference requires numeric values on both sides");
        }
        if (signedDifference != null && absoluteDifference.compareTo(signedDifference.abs()) != 0) {
            throw new IllegalArgumentException("absoluteDifference must equal the magnitude of signedDifference");
        }
        if (percentageDifference != null && percentageDifference.signum() < 0) {
            throw new IllegalArgumentException("percentageDifference must not be negative");
        }
    }

    @Override
    public String toString() {
        return "FinancialDifference[available=" + (signedDifference != null) + "]";
    }
}
