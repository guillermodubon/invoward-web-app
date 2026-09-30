package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChanged;
import io.github.guillermodubon.invoward.identity.application.port.UserSessionInvalidator;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;

/** Expires all account sessions after commit, then sends a best-effort notice to the old email. */
public class EmailChangedNotificationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailChangedNotificationListener.class);

    private final UserSessionInvalidator sessionInvalidator;
    private final EmailChangedNotificationFactory emailFactory;
    private final EmailSender emailSender;

    public EmailChangedNotificationListener(
            UserSessionInvalidator sessionInvalidator,
            EmailChangedNotificationFactory emailFactory,
            EmailSender emailSender) {
        this.sessionInvalidator = Objects.requireNonNull(sessionInvalidator);
        this.emailFactory = Objects.requireNonNull(emailFactory);
        this.emailSender = Objects.requireNonNull(emailSender);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailChanged(EmailChanged event) {
        sessionInvalidator.invalidateAll(event.userId());
        try {
            emailSender.send(emailFactory.create(event));
        } catch (EmailDeliveryException exception) {
            LOGGER.warn("operation=email_changed result=failure failureType={}", exception.failure());
        }
    }
}
