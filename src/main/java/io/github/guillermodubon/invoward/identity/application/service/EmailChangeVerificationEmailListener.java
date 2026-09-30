package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChangeVerificationRequested;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;

/** Delivers email-change confirmations only after their token transaction commits. */
@Component
public class EmailChangeVerificationEmailListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailChangeVerificationEmailListener.class);

    private final EmailChangeVerificationEmailFactory emailFactory;
    private final EmailSender emailSender;

    public EmailChangeVerificationEmailListener(
            EmailChangeVerificationEmailFactory emailFactory,
            EmailSender emailSender) {
        this.emailFactory = Objects.requireNonNull(emailFactory);
        this.emailSender = Objects.requireNonNull(emailSender);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailChangeVerificationRequested(EmailChangeVerificationRequested request) {
        try {
            emailSender.send(emailFactory.create(request));
        } catch (EmailDeliveryException exception) {
            LOGGER.warn("operation=email_change_requested result=failure failureType={}", exception.failure());
        }
    }
}
