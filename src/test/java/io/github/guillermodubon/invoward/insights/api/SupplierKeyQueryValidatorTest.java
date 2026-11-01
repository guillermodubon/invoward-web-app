package io.github.guillermodubon.invoward.insights.api;

import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierKeyException;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupplierKeyQueryValidatorTest {

    @Test
    void preservesExactDecodedCanonicalKeyIncludingPersistableUnicodeAndPunctuation() {
        String key = "acme & sons (東京) + 50% #1";

        assertEquals(new SupplierKey(key), SupplierKeyQueryValidator.validate(key));
    }

    @Test
    void acceptsThePersistedColumnLimitInUnicodeCodePoints() {
        String key = "😀".repeat(SupplierKey.MAX_CODE_POINTS);

        assertEquals(key, SupplierKeyQueryValidator.validate(key).value());
    }

    @Test
    void rejectsMissingBlankOversizedControlAndPathUnsafeValuesWithOneSafeError() {
        for (String value : new String[] {
                null,
                "",
                " \t ",
                "😀".repeat(SupplierKey.MAX_CODE_POINTS + 1),
                "supplier\nname",
                "supplier/name",
                "supplier\\name",
                ".",
                "..",
                "\uD800"
        }) {
            InvalidSupplierKeyException exception = assertThrows(
                    InvalidSupplierKeyException.class,
                    () -> SupplierKeyQueryValidator.validate(value));
            assertEquals("Supplier key is invalid.", exception.getMessage());
        }
    }

    @Test
    void doesNotTrimOrCaseFoldTheInputIntoAnotherSupplierIdentity() {
        String noncanonicalInput = "ACME Supplies";

        assertEquals(noncanonicalInput, SupplierKeyQueryValidator.validate(noncanonicalInput).value());
    }

    @Test
    void rejectsPathUnsafeKeysEvenWhenTheyAreValidPersistedCanonicalValues() {
        for (String value : new String[] {"supplier/name", "supplier\\name", ".", ".."}) {
            assertThrows(InvalidSupplierKeyException.class, () -> SupplierKeyQueryValidator.validate(value));
        }
    }

    @Test
    void preservesUnknownWellFormedKeyForTheLaterOwnerScopedLookup() {
        String unknownKey = "no completed supplier with this key";

        assertEquals(unknownKey, SupplierKeyQueryValidator.validate(unknownKey).value());
    }
}
