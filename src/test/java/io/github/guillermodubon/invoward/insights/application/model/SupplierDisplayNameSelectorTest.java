package io.github.guillermodubon.invoward.insights.application.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupplierDisplayNameSelectorTest {

    private static final SupplierKey KEY = new SupplierKey("acme supplies");

    @Test
    void keepsTheLatestCompletedNonblankRepositoryProjectionExactly() {
        String latestCompletedName = "  ACME Latest Label  ";

        assertEquals(latestCompletedName, SupplierDisplayNameSelector.select(KEY, latestCompletedName));
    }

    @Test
    void fallsBackToTheExactCanonicalKeyWhenThereIsNoNonblankCompletedName() {
        assertEquals(KEY.value(), SupplierDisplayNameSelector.select(KEY, null));
        assertEquals(KEY.value(), SupplierDisplayNameSelector.select(KEY, " \t "));
    }

    @Test
    void requiresAKeyForTheFallback() {
        assertThrows(NullPointerException.class, () -> SupplierDisplayNameSelector.select(null, null));
    }
}
