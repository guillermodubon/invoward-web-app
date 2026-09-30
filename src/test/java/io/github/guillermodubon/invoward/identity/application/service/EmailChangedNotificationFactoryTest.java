package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChanged;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmailChangedNotificationFactoryTest {

    @Test
    void createsSecurityNoticeForPreviousAddressWithoutNewAddressOrSecrets() {
        String previousEmail = "previous@example.test";
        TransactionalEmail email = new EmailChangedNotificationFactory().create(new EmailChanged(
                UUID.randomUUID(), previousEmail));

        assertEquals(previousEmail, email.recipient());
        assertEquals("Your InvoWard email address was changed", email.subject());
        org.junit.jupiter.api.Assertions.assertTrue(email.textBody().contains("If you did not make this change"));
        assertFalse(email.textBody().contains(previousEmail));
        assertFalse(email.htmlBody().contains(previousEmail));
    }
}
