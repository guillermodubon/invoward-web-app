package io.github.guillermodubon.invoward.reconciliation.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LineItemCodeNormalizerTest {

    private final LineItemCodeNormalizer normalizer = new LineItemCodeNormalizer();

    @Test
    void appliesNfkcStripAndRootCaseNormalization() {
        assertEquals("ABC-123", normalizer.normalize("  ＡＢＣ－１２３  "));
        assertEquals("I", normalizer.normalize(" i "));
    }

    @Test
    void preservesIdentifierPunctuationAndDigits() {
        assertEquals("AB-C/12.3", normalizer.normalize("ab-c/12.3"));
        assertEquals("AB-C/12.3", normalizer.normalize("AB-C/12.3"));
        assertNotEquals(normalizer.normalize("AB-C/12.3"), normalizer.normalize("ABC123"));
    }

    @Test
    void treatsMissingOrWhitespaceOnlyCodesAsAbsent() {
        assertNull(normalizer.normalize(null));
        assertNull(normalizer.normalize(" \t\n "));
    }
}
