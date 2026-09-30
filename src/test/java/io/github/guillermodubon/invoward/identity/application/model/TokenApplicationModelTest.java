package io.github.guillermodubon.invoward.identity.application.model;

import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenApplicationModelTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TOKEN_HASH = "a".repeat(64);

    @Test
    void emailVerificationTokenRedactsHashAndTargetEmail() {
        EmailVerificationToken token = new EmailVerificationToken(
                UUID.randomUUID(),
                TOKEN_HASH,
                EmailVerificationPurpose.EMAIL_CHANGE,
                "sensitive-target@example.com",
                CREATED_AT.plusSeconds(60),
                null,
                CREATED_AT);

        assertFalse(token.toString().contains(TOKEN_HASH));
        assertFalse(token.toString().contains("sensitive-target@example.com"));
    }

    @Test
    void passwordResetTokenRedactsHash() {
        PasswordResetToken token = new PasswordResetToken(
                UUID.randomUUID(), TOKEN_HASH, CREATED_AT.plusSeconds(60), null, CREATED_AT);

        assertFalse(token.toString().contains(TOKEN_HASH));
    }

    @Test
    void rejectsMalformedHashAndInvalidTimeWindows() {
        UUID userId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetToken(
                userId, "not-a-hash", CREATED_AT.plusSeconds(60), null, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetToken(
                userId, TOKEN_HASH, CREATED_AT, null, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new EmailVerificationToken(
                userId, TOKEN_HASH, EmailVerificationPurpose.REGISTRATION, "user@example.com",
                CREATED_AT.plusSeconds(60), CREATED_AT.minusSeconds(1), CREATED_AT));
    }
}
