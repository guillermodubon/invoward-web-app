package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordChanged;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordChangedEmailFactoryTest {

    @Test
    void createsSecurityNotificationWithoutCredentialsOrSessionInformation() {
        TransactionalEmail email = new PasswordChangedEmailFactory().create(
                new PasswordChanged(UUID.randomUUID(), "recipient@example.com"));

        assertEquals("recipient@example.com", email.recipient());
        assertEquals("Your InvoWard password was changed", email.subject());
        assertTrue(email.textBody().contains("password was changed"));
        assertTrue(email.textBody().contains("If you did not make this change"));
        assertTrue(email.htmlBody().contains("If you did not make this change"));
        for (String sensitiveData : new String[]{"password_hash", "new password", "token", "session"}) {
            assertFalse(email.textBody().toLowerCase().contains(sensitiveData));
            assertFalse(email.htmlBody().toLowerCase().contains(sensitiveData));
        }
    }
}
