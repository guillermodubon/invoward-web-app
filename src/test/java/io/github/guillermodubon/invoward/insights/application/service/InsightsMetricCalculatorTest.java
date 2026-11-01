package io.github.guillermodubon.invoward.insights.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InsightsMetricCalculatorTest {

    private final InsightsMetricCalculator calculator = new InsightsMetricCalculator();

    @Test
    void returnsNullForZeroDenominatorAndZeroForZeroNumeratorWithData() {
        assertNull(calculator.percentage(0, 0));
        assertEquals(new BigDecimal("0.00"), calculator.percentage(0, 8));
    }

    @Test
    void calculatesPercentagesWithTwoDecimalPlacesAndHalfUpRounding() {
        assertEquals(new BigDecimal("33.33"), calculator.percentage(1, 3));
        assertEquals(new BigDecimal("66.67"), calculator.percentage(2, 3));
        assertEquals(new BigDecimal("100.00"), calculator.percentage(3, 3));
        assertEquals(2, calculator.percentage(1, 3).scale());
    }

    @Test
    void rejectsNegativeOrImpossibleCounts() {
        assertThrows(IllegalArgumentException.class, () -> calculator.percentage(-1, 3));
        assertThrows(IllegalArgumentException.class, () -> calculator.percentage(0, -1));
        assertThrows(IllegalArgumentException.class, () -> calculator.percentage(2, 1));
    }

    @Test
    void sumsCountsWithoutAcceptingNegativeValuesOrOverflow() {
        assertEquals(3, calculator.sumCounts(1, 2));
        assertEquals(Long.MAX_VALUE, calculator.sumCounts(Long.MAX_VALUE - 1, 1));
        assertThrows(IllegalArgumentException.class, () -> calculator.sumCounts(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> calculator.sumCounts(Long.MAX_VALUE, 1));
    }

    @Test
    void calculatorUsesNoBinaryFloatingPointTypes() throws NoSuchMethodException {
        assertEquals(BigDecimal.class, InsightsMetricCalculator.class.getMethod(
                "percentage", long.class, long.class).getReturnType());
        assertThrows(NoSuchMethodException.class, () -> InsightsMetricCalculator.class.getMethod(
                "percentage", double.class, double.class));
    }
}
