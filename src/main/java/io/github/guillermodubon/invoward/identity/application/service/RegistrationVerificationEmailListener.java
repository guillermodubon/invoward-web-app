package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.RegistrationVerificationRequested;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;

/** Delivers registration verification email only after the registration transaction commits. */
@Component
public class RegistrationVerificationEmailListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationVerificationEmailListener.class);

    private final RegistrationVerificationEmailFactory emailFactory;
    private final EmailSender emailSender;

    public RegistrationVerificationEmailListener(
            RegistrationVerificationEmailFactory emailFactory,
            EmailSender emailSender) {
        this.emailFactory = Objects.requireNonNull(emailFactory);
        this.emailSender = Objects.requireNonNull(emailSender);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistrationVerificationRequested(RegistrationVerificationRequested request) {
        try {
            emailSender.send(emailFactory.create(request));
        } catch (EmailDeliveryException exception) {
            LOGGER.warn("operation=registration_verification_email result=failure failureType={}",
                    exception.failure());
        }
    }
}
