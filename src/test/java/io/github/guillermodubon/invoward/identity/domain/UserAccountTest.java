package io.github.guillermodubon.invoward.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserAccountTest {

    @Test
    void normalizesEmailByTrimmingAndLowercasingWithoutChangingInternalCharacters() {
        assertEquals("user.name+tag@example.com", UserAccount.normalizeEmail(" User.Name+tag@Example.COM "));
        assertEquals("user@example.com", UserAccount.normalizeEmail("\u00A0User@Example.COM\u00A0"));
    }

    @Test
    void acceptsEmailAtThreeHundredTwentyCodePointsAndRejectsLongerValues() {
        String maximumEmail = "a".repeat(308) + "@example.com";

        assertEquals(320, maximumEmail.codePointCount(0, maximumEmail.length()));
        assertEquals(maximumEmail, UserAccount.normalizeEmail(maximumEmail));
        assertThrows(IllegalArgumentException.class,
                () -> UserAccount.normalizeEmail("a".repeat(309) + "@example.com"));
    }

    @Test
    void rejectsBlankEmail() {
        assertThrows(IllegalArgumentException.class, () -> UserAccount.normalizeEmail(" \t\n"));
    }

    @Test
    void normalizesDisplayNameAndPreservesUnicodeAndInternalWhitespace() {
        assertEquals("Guillermo", UserAccount.normalizeDisplayName(" Guillermo "));
        assertEquals("Guillermo Hernández 🚀", UserAccount.normalizeDisplayName(" Guillermo Hernández 🚀 "));
        assertEquals("Guillermo  Hernández", UserAccount.normalizeDisplayName(" Guillermo  Hernández "));
    }

    @Test
    void rejectsBlankDisplayNameAndEnforcesUnicodeCodePointMaximum() {
        assertThrows(IllegalArgumentException.class, () -> UserAccount.normalizeDisplayName(" \t\n"));
        assertThrows(IllegalArgumentException.class, () -> UserAccount.normalizeDisplayName("\u00A0"));
        assertEquals("😀".repeat(120), UserAccount.normalizeDisplayName("😀".repeat(120)));
        assertThrows(IllegalArgumentException.class,
                () -> UserAccount.normalizeDisplayName("😀".repeat(121)));
    }

    @Test
    void accountStringRepresentationRedactsPasswordHashAndPersonalDetails() {
        String passwordHash = "$argon2id$test-hash-value";
        UserAccount account = new UserAccount(
                UUID.randomUUID(),
                "Guillermo Hernández",
                "user@example.com",
                passwordHash,
                false,
                UserStatus.PENDING_VERIFICATION,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));

        assertFalse(account.toString().contains(passwordHash));
        assertFalse(account.toString().contains("user@example.com"));
        assertFalse(account.toString().contains("Guillermo Hernández"));
        assertEquals("user@example.com", account.email());
        assertEquals("Guillermo Hernández", account.displayName());
    }
}
