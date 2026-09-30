package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChangeVerificationRequested;
import io.github.guillermodubon.invoward.identity.application.port.EmailChangeConfirmationLinkBuilder;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Builds provider-neutral confirmation email content for a requested address change. */
@Component
public class EmailChangeVerificationEmailFactory {

    private static final String SUBJECT = "Confirm your new InvoWard email";
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final EmailChangeConfirmationLinkBuilder linkBuilder;

    public EmailChangeVerificationEmailFactory(EmailChangeConfirmationLinkBuilder linkBuilder) {
        this.linkBuilder = Objects.requireNonNull(linkBuilder);
    }

    public TransactionalEmail create(EmailChangeVerificationRequested request) {
        Objects.requireNonNull(request, "request must not be null");

        String confirmationUrl = linkBuilder.build(request.rawToken());
        String expiryMessage = "This confirmation link expires at "
                + EXPIRY_FORMAT.format(request.expiresAt()) + " (UTC).";
        String ignoreMessage = "If you did not request this email change, you can ignore this message.";
        String textBody = "Confirm your new InvoWard email address using this link:\n\n"
                + confirmationUrl + "\n\n"
                + expiryMessage + "\n\n"
                + ignoreMessage;
        String htmlBody = "<p>Confirm your new InvoWard email address using this link:</p>"
                + "<p><a href=\"" + escapeHtml(confirmationUrl) + "\">Confirm email address</a></p>"
                + "<p>" + escapeHtml(expiryMessage) + "</p>"
                + "<p>" + escapeHtml(ignoreMessage) + "</p>";

        return new TransactionalEmail(request.recipient(), SUBJECT, textBody, htmlBody);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
