package io.github.guillermodubon.invoward.analysis.api.model;

import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/** Client-selectable comparison tolerance; ownership and Analysis state are server-controlled. */
public record CreateAnalysisRequest(
        BigDecimal priceTolerancePercent,
        @PositiveOrZero(message = "Absolute tolerance must not be negative")
        @Digits(integer = 15, fraction = 4,
                message = "Absolute tolerance must fit NUMERIC(19,4)")
        BigDecimal priceToleranceAbsolute) {

    private static final List<BigDecimal> ALLOWED_PERCENTAGES = List.of(
            BigDecimal.ZERO,
            BigDecimal.ONE,
            new BigDecimal("3"),
            new BigDecimal("5"));

    @AssertTrue(message = "Price tolerance percent must be one of 0, 1, 3, or 5")
    public boolean isPriceTolerancePercentAllowed() {
        return priceTolerancePercent == null || ALLOWED_PERCENTAGES.stream()
                .anyMatch(allowed -> allowed.compareTo(priceTolerancePercent) == 0);
    }

    public PriceTolerance toPriceTolerance() {
        BigDecimal percentage = priceTolerancePercent == null
                ? BigDecimal.ZERO
                : priceTolerancePercent;
        return new PriceTolerance(percentage, priceToleranceAbsolute);
    }
}
