package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonetaryToleranceEvaluatorTest {

    private final MonetaryToleranceEvaluator evaluator = new MonetaryToleranceEvaluator();

    @Test
    void exactToleranceUsesNumericEqualityAcrossDifferentScales() {
        PriceTolerance exact = PriceTolerance.exactMatch();

        assertTrue(evaluator.isWithinTolerance(new BigDecimal("10.0"), new BigDecimal("10.0000"), exact));
        assertFalse(evaluator.isWithinTolerance(new BigDecimal("10.0"), new BigDecimal("10.0001"), exact));
    }

    @Test
    void acceptsTheExactPercentageBoundaryAndRejectsValuesBeyondIt() {
        PriceTolerance onePercent = new PriceTolerance(BigDecimal.ONE, null);

        assertEquals(0, new BigDecimal("1.0000")
                .compareTo(evaluator.effectiveAllowance(new BigDecimal("100.0000"), onePercent)));
        assertTrue(evaluator.isWithinTolerance(new BigDecimal("100"), new BigDecimal("101"), onePercent));
        assertFalse(evaluator.isWithinTolerance(new BigDecimal("100"), new BigDecimal("101.0001"), onePercent));
    }

    @Test
    void usesTheGreaterOfPercentageAndAbsoluteAllowances() {
        PriceTolerance percentageWins = new PriceTolerance(new BigDecimal("3"), new BigDecimal("2.0000"));
        PriceTolerance absoluteWins = new PriceTolerance(BigDecimal.ONE, new BigDecimal("2.0000"));

        assertEquals(0, new BigDecimal("3.0000")
                .compareTo(evaluator.effectiveAllowance(new BigDecimal("100"), percentageWins)));
        assertEquals(0, new BigDecimal("2.0000")
                .compareTo(evaluator.effectiveAllowance(new BigDecimal("100"), absoluteWins)));
        assertTrue(evaluator.isWithinTolerance(new BigDecimal("100"), new BigDecimal("102"), absoluteWins));
        assertFalse(evaluator.isWithinTolerance(new BigDecimal("100"), new BigDecimal("102.0001"), absoluteWins));
    }

    @Test
    void preservesExactFractionalPercentageAllowanceWithoutRoundingItToCurrencyScale() {
        PriceTolerance threePercent = new PriceTolerance(new BigDecimal("3"), null);

        assertEquals(0, new BigDecimal("0.000003")
                .compareTo(evaluator.effectiveAllowance(new BigDecimal("0.0001"), threePercent)));
    }

    @Test
    void usesAbsoluteToleranceWhenReferenceIsZero() {
        PriceTolerance percentageOnly = new PriceTolerance(new BigDecimal("5"), null);
        PriceTolerance withAbsolute = new PriceTolerance(new BigDecimal("5"), new BigDecimal("0.2500"));

        assertEquals(0, BigDecimal.ZERO.compareTo(evaluator.effectiveAllowance(BigDecimal.ZERO, percentageOnly)));
        assertFalse(evaluator.isWithinTolerance(BigDecimal.ZERO, new BigDecimal("0.01"), percentageOnly));
        assertTrue(evaluator.isWithinTolerance(BigDecimal.ZERO, new BigDecimal("0.2500"), withAbsolute));
    }

    @Test
    void handlesNullValuesWithoutSubstitutingZero() {
        PriceTolerance tolerance = PriceTolerance.exactMatch();

        assertTrue(evaluator.isWithinTolerance(null, null, tolerance));
        assertFalse(evaluator.isWithinTolerance(null, BigDecimal.ZERO, tolerance));
        assertFalse(evaluator.isWithinTolerance(BigDecimal.ZERO, null, tolerance));
    }
}
