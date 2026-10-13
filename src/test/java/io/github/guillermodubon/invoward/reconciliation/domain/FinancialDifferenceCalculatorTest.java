package io.github.guillermodubon.invoward.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FinancialDifferenceCalculatorTest {

    private final FinancialDifferenceCalculator calculator = new FinancialDifferenceCalculator();

    @Test
    void calculatesSignedAndAbsoluteDifferenceInBothDirections() {
        FinancialDifference above = calculator.calculate(new BigDecimal("10.0000"), new BigDecimal("12.5000"));
        FinancialDifference below = calculator.calculate(new BigDecimal("10.0000"), new BigDecimal("7.5000"));

        assertEquals(new BigDecimal("2.5000"), above.signedDifference());
        assertEquals(new BigDecimal("2.5000"), above.absoluteDifference());
        assertEquals(new BigDecimal("-2.5000"), below.signedDifference());
        assertEquals(new BigDecimal("2.5000"), below.absoluteDifference());
    }

    @Test
    void calculatesAbsolutePercentageRelativeToAbsoluteReferenceAtScaleSixHalfUp() {
        FinancialDifference difference = calculator.calculate(new BigDecimal("-3"), new BigDecimal("-2"));

        assertEquals(new BigDecimal("33.333333"), difference.percentageDifference());
        assertEquals(new BigDecimal("33.333333"),
                calculator.calculate(new BigDecimal("3"), new BigDecimal("4")).percentageDifference());
        FinancialDifference negativeDirection = calculator.calculate(new BigDecimal("-3"), new BigDecimal("-4"));
        assertEquals(new BigDecimal("-1"), negativeDirection.signedDifference());
        assertEquals(new BigDecimal("33.333333"), negativeDirection.percentageDifference());
        assertEquals(new BigDecimal("1.234568"),
                calculator.calculate(new BigDecimal("2"), new BigDecimal("2.02469135"))
                        .percentageDifference());
    }

    @Test
    void leavesPercentageUndefinedForZeroReferenceAndAllDifferencesAbsentForNullInputs() {
        FinancialDifference zeroReference = calculator.calculate(BigDecimal.ZERO, BigDecimal.ONE);
        FinancialDifference bothNull = calculator.calculate(null, null);
        FinancialDifference oneNull = calculator.calculate(BigDecimal.ONE, null);

        assertEquals(BigDecimal.ONE, zeroReference.signedDifference());
        assertEquals(BigDecimal.ONE, zeroReference.absoluteDifference());
        assertNull(zeroReference.percentageDifference());
        assertNull(bothNull.signedDifference());
        assertNull(bothNull.absoluteDifference());
        assertNull(bothNull.percentageDifference());
        assertNull(oneNull.signedDifference());
        assertNull(oneNull.absoluteDifference());
    }
}
