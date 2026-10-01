package io.github.guillermodubon.invoward.analysis.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PriceToleranceTest {

    @Test
    void acceptsOnlyTheApprovedPercentages() {
        for (String percentage : new String[]{"0", "1", "3", "5", "3.00"}) {
            assertEquals(0, new PriceTolerance(new BigDecimal(percentage), null)
                    .priceTolerancePercent().compareTo(new BigDecimal(percentage)));
        }

        for (String percentage : new String[]{"-1", "2", "4", "100"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PriceTolerance(new BigDecimal(percentage), null));
        }
        assertThrows(NullPointerException.class, () -> new PriceTolerance(null, null));
    }

    @Test
    void acceptsMissingZeroAndPositiveAbsoluteTolerance() {
        assertNull(PriceTolerance.exactMatch().priceToleranceAbsolute());
        assertEquals(0, new PriceTolerance(BigDecimal.ZERO, BigDecimal.ZERO)
                .priceToleranceAbsolute().compareTo(BigDecimal.ZERO));
        assertEquals(0, new PriceTolerance(BigDecimal.ONE, new BigDecimal("125.4321"))
                .priceToleranceAbsolute().compareTo(new BigDecimal("125.4321")));
        assertEquals(0, new PriceTolerance(BigDecimal.ONE, new BigDecimal("999999999999999.9999"))
                .priceToleranceAbsolute().compareTo(new BigDecimal("999999999999999.9999")));
    }

    @Test
    void rejectsNegativeAndOverpreciseAbsoluteToleranceWithoutRounding() {
        for (String absolute : new String[]{"-0.01", "1.00001", "1000000000000000", "1000000000000000.0000"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PriceTolerance(BigDecimal.ZERO, new BigDecimal(absolute)), absolute);
        }
    }
}
