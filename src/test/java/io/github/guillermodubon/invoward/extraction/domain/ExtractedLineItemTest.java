package io.github.guillermodubon.invoward.extraction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtractedLineItemTest {

    @Test
    void stripsBusinessTextAndNormalizesNumericValuesWithoutRounding() {
        ExtractedLineItem line = new ExtractedLineItem(
                0, " SKU-1 ", "  Café Ａ  ", new BigDecimal("1.2500"), " ea ",
                new BigDecimal("12.50000"), BigDecimal.ZERO, null, new BigDecimal("15"),
                1, " Café Ａ ", null);

        assertEquals("SKU-1", line.itemCode());
        assertEquals("Café Ａ", line.description());
        assertEquals("ea", line.unit());
        assertEquals(new BigDecimal("12.5000"), line.unitPrice());
        assertEquals("café a", line.normalizedDescription());
        assertEquals(" Café Ａ ", line.sourceText());
    }

    @Test
    void rejectsInvalidLineValuesAndOverlongTextByCodePointCount() {
        assertThrows(IllegalArgumentException.class, () -> line(-1, "Item"));
        assertThrows(IllegalArgumentException.class, () -> line(0, "   "));
        assertThrows(IllegalArgumentException.class, () -> line(0, "x".repeat(2001)));
        assertThrows(IllegalArgumentException.class, () -> new ExtractedLineItem(
                0, null, "Item", BigDecimal.ZERO, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExtractedLineItem(
                0, null, "Item", null, null, new BigDecimal("-0.01"), null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExtractedLineItem(
                0, null, "Item", null, null, null, null, null, null, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExtractedLineItem(
                0, "😀".repeat(121), "Item", null, null, null, null, null, null, null, null, null));
    }

    @Test
    void rejectsNumeric19_4ScaleAndPrecisionOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new ExtractedLineItem(
                0, null, "Item", new BigDecimal("1.00001"), null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExtractedLineItem(
                0, null, "Item", null, null, new BigDecimal("1000000000000000"), null, null, null,
                null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExtractedLineItem(
                0, null, "Item", null, null, new BigDecimal("1E+2147483647"), null, null, null,
                null, null, null));
    }

    @Test
    void nullsBlankSourceEvidence() {
        ExtractedLineItem line = new ExtractedLineItem(
                0, null, "Item", null, null, null, null, null, null, null, "  ", null);
        assertNull(line.sourceText());
    }

    private static ExtractedLineItem line(int position, String description) {
        return new ExtractedLineItem(
                position, null, description, null, null, null, null, null, null, null, null, null);
    }
}
