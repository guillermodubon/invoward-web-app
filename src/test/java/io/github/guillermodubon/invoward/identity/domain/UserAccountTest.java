package io.github.guillermodubon.invoward.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void activatesOnlyPendingUnverifiedRegistrationAndKeepsPersistenceVersionSnapshot() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant activatedAt = createdAt.plusSeconds(30);
        UserAccount pending = account(UserStatus.PENDING_VERIFICATION, false, createdAt);

        UserAccount activated = pending.activateVerifiedRegistration(activatedAt);

        assertEquals(UserStatus.ACTIVE, activated.status());
        assertTrue(activated.emailVerified());
        assertEquals(0, activated.version());
        assertEquals(createdAt, activated.createdAt());
        assertEquals(activatedAt, activated.updatedAt());
        assertEquals(activated, activated.activateVerifiedRegistration(activatedAt));
        assertThrows(IllegalStateException.class,
                () -> account(UserStatus.DISABLED, false, createdAt).activateVerifiedRegistration(activatedAt));
    }

    @Test
    void returnsAccountCopiesForDisplayNamePasswordAndEmailMutations() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant changedAt = createdAt.plusSeconds(60);
        UserAccount active = account(UserStatus.ACTIVE, true, createdAt);

        UserAccount renamed = active.updateDisplayName("  New  Display Name  ", changedAt);
        UserAccount passwordChanged = renamed.updatePasswordHash("$argon2id$new-hash", changedAt);
        UserAccount emailChanged = passwordChanged.updateEmail(" NEW@example.com ", changedAt);

        assertEquals("New  Display Name", renamed.displayName());
        assertEquals(active.email(), renamed.email());
        assertEquals("$argon2id$new-hash", passwordChanged.passwordHash());
        assertEquals("new@example.com", emailChanged.email());
        assertTrue(emailChanged.emailVerified());
        assertEquals(active.version(), emailChanged.version());
        assertEquals(changedAt, emailChanged.updatedAt());
        assertThrows(IllegalStateException.class,
                () -> account(UserStatus.DISABLED, true, createdAt)
                        .updateDisplayName("Name", changedAt));
    }

    private static UserAccount account(UserStatus status, boolean emailVerified, Instant timestamp) {
        return new UserAccount(
                UUID.randomUUID(), "Current Name", "current@example.com", "$argon2id$current",
                emailVerified, status, 0, timestamp, timestamp);
    }
}
