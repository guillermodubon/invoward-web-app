package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordChanged;
import io.github.guillermodubon.invoward.identity.application.port.UserSessionInvalidator;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;

/** Invalidates sessions after commit, then sends a best-effort security notification. */
public class PasswordChangedListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordChangedListener.class);

    private final UserSessionInvalidator sessionInvalidator;
    private final PasswordChangedEmailFactory emailFactory;
    private final EmailSender emailSender;

    public PasswordChangedListener(
            UserSessionInvalidator sessionInvalidator,
            PasswordChangedEmailFactory emailFactory,
            EmailSender emailSender) {
        this.sessionInvalidator = Objects.requireNonNull(sessionInvalidator);
        this.emailFactory = Objects.requireNonNull(emailFactory);
        this.emailSender = Objects.requireNonNull(emailSender);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordChanged(PasswordChanged event) {
        sessionInvalidator.invalidateAll(event.userId());
        try {
            emailSender.send(emailFactory.create(event));
        } catch (EmailDeliveryException exception) {
            LOGGER.warn("operation=password_changed result=failure failureType={}", exception.failure());
        }
    }
}
