package io.github.guillermodubon.invoward.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CanonicalValueFormatterTest {

    private final CanonicalValueFormatter formatter = new CanonicalValueFormatter();

    @Test
    void stripsInsignificantZeroesWithoutScientificNotation() {
        assertEquals("1250", formatter.format(new BigDecimal("1.250E+3")));
        assertEquals("-0.125", formatter.format(new BigDecimal("-0.125000")));
    }

    @Test
    void formatsEveryNumericZeroAsZeroAndPreservesNull() {
        assertEquals("0", formatter.format(new BigDecimal("0.0000")));
        assertEquals("0", formatter.format(new BigDecimal("-0.000")));
        assertNull(formatter.format(null));
    }
}
