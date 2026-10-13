package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;

import java.math.BigDecimal;
import java.util.Objects;

/** Applies the approved percentage/absolute tolerance rule without rounding financial values. */
public final class MonetaryToleranceEvaluator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public boolean isWithinTolerance(BigDecimal reference, BigDecimal invoice, PriceTolerance tolerance) {
        Objects.requireNonNull(tolerance, "tolerance must not be null");
        if (reference == null || invoice == null) {
            return reference == null && invoice == null;
        }

        BigDecimal absoluteDifference = invoice.subtract(reference).abs();
        return absoluteDifference.compareTo(effectiveAllowance(reference, tolerance)) <= 0;
    }

    public BigDecimal effectiveAllowance(BigDecimal reference, PriceTolerance tolerance) {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(tolerance, "tolerance must not be null");

        BigDecimal percentageAllowance = reference.abs()
                .multiply(tolerance.priceTolerancePercent())
                .divide(ONE_HUNDRED);
        BigDecimal absoluteAllowance = tolerance.priceToleranceAbsolute() == null
                ? BigDecimal.ZERO
                : tolerance.priceToleranceAbsolute();
        return percentageAllowance.max(absoluteAllowance);
    }
}
