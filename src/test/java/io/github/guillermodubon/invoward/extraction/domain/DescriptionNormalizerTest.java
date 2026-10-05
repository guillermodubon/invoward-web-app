package io.github.guillermodubon.invoward.extraction.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DescriptionNormalizerTest {

    @Test
    void appliesNfkcStripWhitespaceCollapseAndRootLowercase() {
        assertEquals("acme invoice item i", DescriptionNormalizer.normalize("  Ａcme\tInvoice  Item I  "));
    }
}
