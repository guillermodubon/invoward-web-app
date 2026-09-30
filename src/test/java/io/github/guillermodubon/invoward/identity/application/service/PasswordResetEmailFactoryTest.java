package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordResetRequested;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordResetEmailFactoryTest {

    private static final String RAW_TOKEN = "private-reset-token";
    private static final String RESET_URL = "https://app.example/reset-password?token=private-reset-token";
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-29T18:30:00Z");

    @Test
    void createsResetEmailWithLinkExpiryAndIgnoreInstructionOnly() {
        PasswordResetEmailFactory factory = new PasswordResetEmailFactory(token -> {
            assertEquals(RAW_TOKEN, token);
            return RESET_URL;
        });

        TransactionalEmail email = factory.create(new PasswordResetRequested(
                "recipient@example.com", RAW_TOKEN, EXPIRES_AT));

        assertEquals("recipient@example.com", email.recipient());
        assertEquals("Reset your InvoWard password", email.subject());
        assertTrue(email.textBody().contains("A password reset was requested"));
        assertTrue(email.textBody().contains(RESET_URL));
        assertTrue(email.textBody().contains("expires at 2026-09-29T18:30:00Z (UTC)"));
        assertTrue(email.textBody().contains("If you did not request a password reset"));
        assertTrue(email.htmlBody().contains("href=\"" + RESET_URL + "\""));
        assertTrue(email.htmlBody().contains("expires at 2026-09-29T18:30:00Z (UTC)"));
        assertTrue(email.htmlBody().contains("If you did not request a password reset"));
        assertFalse(email.textBody().contains("password_hash"));
        assertFalse(email.textBody().contains("session"));
    }
}
