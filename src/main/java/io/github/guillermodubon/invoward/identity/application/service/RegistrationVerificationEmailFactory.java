package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.RegistrationVerificationRequested;
import io.github.guillermodubon.invoward.identity.application.port.RegistrationVerificationLinkBuilder;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Composes provider-neutral content for a registration verification message. */
@Component
public class RegistrationVerificationEmailFactory {

    private static final String SUBJECT = "Verify your InvoWard email";
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final RegistrationVerificationLinkBuilder linkBuilder;

    public RegistrationVerificationEmailFactory(RegistrationVerificationLinkBuilder linkBuilder) {
        this.linkBuilder = Objects.requireNonNull(linkBuilder);
    }

    public TransactionalEmail create(RegistrationVerificationRequested request) {
        Objects.requireNonNull(request, "request must not be null");

        String verificationUrl = linkBuilder.build(request.rawVerificationToken());
        String expiresAt = EXPIRY_FORMAT.format(request.expiresAt());
        String expiryMessage = "This verification link expires at " + expiresAt + " (UTC).";
        String ignoreMessage = "If you did not create an InvoWard account, you can ignore this message.";

        String textBody = "Verify your InvoWard email address using this link:\n\n"
                + verificationUrl + "\n\n"
                + expiryMessage + "\n\n"
                + ignoreMessage;
        String htmlBody = "<p>Use this link to verify your InvoWard email address:</p>"
                + "<p><a href=\"" + escapeHtml(verificationUrl) + "\">Verify your email address</a></p>"
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
