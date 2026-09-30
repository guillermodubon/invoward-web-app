package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordChanged;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;

import java.util.Objects;

/** Builds a credential-change notification without including credential or session data. */
public class PasswordChangedEmailFactory {

    private static final String SUBJECT = "Your InvoWard password was changed";
    private static final String TEXT_BODY = "Your InvoWard account password was changed.\n\n"
            + "If you did not make this change, use InvoWard's password-reset flow to secure your account.";
    private static final String HTML_BODY = "<p>Your InvoWard account password was changed.</p>"
            + "<p>If you did not make this change, use InvoWard's password-reset flow "
            + "to secure your account.</p>";

    public TransactionalEmail create(PasswordChanged event) {
        Objects.requireNonNull(event, "event must not be null");
        return new TransactionalEmail(event.recipient(), SUBJECT, TEXT_BODY, HTML_BODY);
    }
}
