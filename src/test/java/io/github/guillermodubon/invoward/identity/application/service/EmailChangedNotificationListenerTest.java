package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChanged;
import io.github.guillermodubon.invoward.identity.application.port.UserSessionInvalidator;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.EmailDeliveryReceipt;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailChangedNotificationListenerTest {

    private static final EmailChanged EVENT = new EmailChanged(
            UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8"), "previous@example.test");

    @Test
    void invalidatesAllSessionsBeforeSendingOldAddressNotification() {
        UserSessionInvalidator invalidator = mock(UserSessionInvalidator.class);
        EmailSender sender = mock(EmailSender.class);
        when(sender.send(any(TransactionalEmail.class))).thenReturn(new EmailDeliveryReceipt("receipt"));
        EmailChangedNotificationListener listener = listener(invalidator, sender);

        listener.onEmailChanged(EVENT);

        InOrder order = inOrder(invalidator, sender);
        order.verify(invalidator).invalidateAll(EVENT.userId());
        order.verify(sender).send(any(TransactionalEmail.class));
    }

    @Test
    void deliveryFailureDoesNotEscapeAfterCommit() {
        UserSessionInvalidator invalidator = mock(UserSessionInvalidator.class);
        EmailSender sender = mock(EmailSender.class);
        when(sender.send(any(TransactionalEmail.class))).thenThrow(new EmailDeliveryException(
                EmailDeliveryFailure.PROVIDER_UNAVAILABLE, "Provider unavailable"));

        assertDoesNotThrow(() -> listener(invalidator, sender).onEmailChanged(EVENT));
    }

    private static EmailChangedNotificationListener listener(
            UserSessionInvalidator invalidator,
            EmailSender sender) {
        return new EmailChangedNotificationListener(invalidator, new EmailChangedNotificationFactory(), sender);
    }
}
