package io.github.guillermodubon.invoward.identity.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyTest {

    @Test
    void rejectsPasswordsShorterThanFifteenCodePoints() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("p".repeat(14)));
    }

    @Test
    void acceptsFifteenCodePoints() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("p".repeat(15)));
    }

    @Test
    void acceptsOneHundredTwentyEightCodePoints() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("p".repeat(128)));
    }

    @Test
    void rejectsPasswordsLongerThanOneHundredTwentyEightCodePoints() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("p".repeat(129)));
    }

    @Test
    void measuresUnicodeByCodePointRatherThanUtf16CodeUnit() {
        String password = "😀".repeat(15);

        assertDoesNotThrow(() -> PasswordPolicy.validate(password));
    }

    @Test
    void acceptsInternalSpaces() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("pass word phrase"));
    }

    @Test
    void rejectsPasswordsContainingOnlyUnicodeWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("\u00A0".repeat(15)));
    }

    @Test
    void doesNotTrimOrUnicodeNormalizeThePassword() {
        String trailingSpaceIsSignificant = "p".repeat(14) + " ";
        String decomposedUnicode = "e\u0301".repeat(8);

        assertDoesNotThrow(() -> PasswordPolicy.validate(trailingSpaceIsSignificant));
        assertDoesNotThrow(() -> PasswordPolicy.validate(decomposedUnicode));
    }

    @Test
    void doesNotRequirePasswordCompositionRules() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("lowercasepassphrase"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("UPPERCASEPASSPHRASE"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("!!!!!!!!!!!!!!!!"));
    }
}
