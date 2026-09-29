package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.RegistrationVerificationRequested;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationVerificationEmailFactoryTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-09-28T18:30:00Z");
    private static final String RAW_TOKEN = "raw+verification-token";
    private static final String VERIFICATION_URL =
            "https://app.example/verify-email?source=registration&token=raw%2Bverification-token";

    @Test
    void createsPlainTextAndHtmlVerificationEmailWithoutPersonalOrPasswordData() {
        RegistrationVerificationEmailFactory factory = new RegistrationVerificationEmailFactory(token -> {
            assertEquals(RAW_TOKEN, token);
            return VERIFICATION_URL;
        });

        TransactionalEmail email = factory.create(new RegistrationVerificationRequested(
                "recipient@example.com", RAW_TOKEN, EXPIRES_AT));

        assertEquals("recipient@example.com", email.recipient());
        assertEquals("Verify your InvoWard email", email.subject());
        assertTrue(email.textBody().contains("Verify your InvoWard email address"));
        assertTrue(email.textBody().contains(VERIFICATION_URL));
        assertTrue(email.textBody().contains("expires at 2026-09-28T18:30:00Z (UTC)"));
        assertTrue(email.textBody().contains("If you did not create an InvoWard account"));
        assertTrue(email.htmlBody().contains(
                "href=\"https://app.example/verify-email?source=registration&amp;token=raw%2Bverification-token\""));
        assertTrue(email.htmlBody().contains("expires at 2026-09-28T18:30:00Z (UTC)"));
        assertTrue(email.htmlBody().contains("If you did not create an InvoWard account"));
        assertFalse(email.textBody().contains("password"));
        assertFalse(email.htmlBody().contains("password"));
        assertFalse(email.textBody().contains("session"));
        assertFalse(email.htmlBody().contains("session"));
    }
}
