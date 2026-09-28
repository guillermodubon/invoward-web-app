package io.github.guillermodubon.invoward.support.email;

import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.EmailDeliveryReceipt;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic in-memory email sender for tests; never registered as a production bean. */
public final class FakeEmailSender implements EmailSender {

    private final List<TransactionalEmail> sentEmails = new ArrayList<>();
    private int nextMessageNumber = 1;
    private EmailDeliveryFailure configuredFailure;

    @Override
    public synchronized EmailDeliveryReceipt send(TransactionalEmail email) {
        Objects.requireNonNull(email, "email must not be null");
        if (configuredFailure != null) {
            throw new EmailDeliveryException(
                    configuredFailure, "Fake email sender is configured to fail: " + configuredFailure);
        }

        sentEmails.add(email);
        return new EmailDeliveryReceipt("fake-email-" + nextMessageNumber++);
    }

    /** Returns an immutable snapshot in the order messages were successfully sent. */
    public synchronized List<TransactionalEmail> sentEmails() {
        return List.copyOf(sentEmails);
    }

    /** Configures subsequent sends to fail with the supplied provider-neutral classification. */
    public synchronized void configureFailure(EmailDeliveryFailure failure) {
        configuredFailure = Objects.requireNonNull(failure, "failure must not be null");
    }

    /** Clears the configured failure without changing captured messages or generated IDs. */
    public synchronized void clearFailure() {
        configuredFailure = null;
    }

    /** Clears captured messages, configured failure, and the deterministic ID sequence. */
    public synchronized void clear() {
        sentEmails.clear();
        configuredFailure = null;
        nextMessageNumber = 1;
    }
}
