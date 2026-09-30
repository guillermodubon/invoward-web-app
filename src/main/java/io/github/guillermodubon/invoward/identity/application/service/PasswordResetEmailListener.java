package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordResetRequested;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;

/** Delivers reset instructions only after token issuance commits. */
@Component
public class PasswordResetEmailListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordResetEmailListener.class);

    private final PasswordResetEmailFactory emailFactory;
    private final EmailSender emailSender;

    public PasswordResetEmailListener(PasswordResetEmailFactory emailFactory, EmailSender emailSender) {
        this.emailFactory = Objects.requireNonNull(emailFactory);
        this.emailSender = Objects.requireNonNull(emailSender);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequested request) {
        try {
            emailSender.send(emailFactory.create(request));
        } catch (EmailDeliveryException exception) {
            LOGGER.warn("operation=password_reset_requested result=failure failureType={}",
                    exception.failure());
        }
    }
}
