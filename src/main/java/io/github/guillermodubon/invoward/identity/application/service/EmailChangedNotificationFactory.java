package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChanged;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;

import java.util.Objects;

/** Creates a security notice for the previous email address without exposing account secrets. */
public class EmailChangedNotificationFactory {

    private static final String SUBJECT = "Your InvoWard email address was changed";
    private static final String TEXT_BODY = "The email address for your InvoWard account was changed.\n\n"
            + "If you did not make this change, contact InvoWard support to secure your account.";
    private static final String HTML_BODY = "<p>The email address for your InvoWard account was changed.</p>"
            + "<p>If you did not make this change, contact InvoWard support to secure your account.</p>";

    public TransactionalEmail create(EmailChanged event) {
        Objects.requireNonNull(event, "event must not be null");
        return new TransactionalEmail(event.previousEmail(), SUBJECT, TEXT_BODY, HTML_BODY);
    }
}
