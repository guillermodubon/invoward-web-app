package io.github.guillermodubon.invoward.reporting.application.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvEscaperTest {

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+SUM(A1:A2)", "-cmd", "@SUM(A1)", "  =1+1"})
    void prefixesFormulaMarkersInUntrustedText(String text) {
        String expected = text.startsWith("  ") ? "'  =1+1" : "'" + text;

        assertEquals(expected, CsvEscaper.escapeText(text));
    }

    @Test
    void normalizesLeadingControlTricksBeforeFormulaCheck() {
        assertEquals("' =1+1", CsvEscaper.escapeText("\t=1+1"));
        assertEquals("'=1+1", CsvEscaper.escapeText("\u0000=1+1"));
        assertEquals("'=1+1", CsvEscaper.escapeText("\u0001=1+1"));
        assertEquals("'=1+1", CsvEscaper.escapeText("\u200B=1+1"));
        assertEquals("'\u200D=1+1", CsvEscaper.escapeText("\u200D=1+1"));
    }

    @Test
    void quotesCommasAndDoublesQuotesUsingRfc4180Rules() {
        assertEquals("\"A, \"\"quoted\"\" value\"", CsvEscaper.escapeText("A, \"quoted\" value"));
    }

    @Test
    void normalizesLineBreaksAndTabsRemovesUnsafeControlsAndPreservesUnicode() {
        assertEquals("café 東京   value", CsvEscaper.escapeText("café 東京\r\n\tvalue\u0000\u0001"));
    }

    @Test
    void nullTextIsAnEmptyCell() {
        assertEquals("", CsvEscaper.escapeText(null));
    }

    @Test
    void preservesUnicodeScriptJoiners() {
        assertEquals("क्\u200Dष", CsvEscaper.escapeText("क्\u200Dष"));
    }
}
