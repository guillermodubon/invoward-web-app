package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChangeVerificationRequested;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailChangeVerificationEmailFactoryTest {

    private static final String RECIPIENT = "new-address@example.com";
    private static final String RAW_TOKEN = "private-email-change-token";
    private static final String CONFIRMATION_URL =
            "https://app.example/verify-email?flow=email-change&token=private-email-change-token";
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-30T18:30:00Z");

    @Test
    void createsConfirmationEmailWithNewAddressLinkExpiryAndIgnoreInstruction() {
        EmailChangeVerificationEmailFactory factory = new EmailChangeVerificationEmailFactory(token -> {
            assertEquals(RAW_TOKEN, token);
            return CONFIRMATION_URL;
        });

        TransactionalEmail email = factory.create(new EmailChangeVerificationRequested(
                RECIPIENT, RAW_TOKEN, EXPIRES_AT));

        assertEquals(RECIPIENT, email.recipient());
        assertEquals("Confirm your new InvoWard email", email.subject());
        assertTrue(email.textBody().contains(CONFIRMATION_URL));
        assertTrue(email.textBody().contains("expires at 2026-09-30T18:30:00Z (UTC)"));
        assertTrue(email.textBody().contains("you can ignore this message"));
        assertTrue(email.htmlBody().contains("href=\""
                + CONFIRMATION_URL.replace("&", "&amp;") + "\""));
        assertFalse(email.textBody().contains("password"));
        assertFalse(email.htmlBody().contains("session"));
    }
}
