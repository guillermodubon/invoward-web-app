package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordResetRequested;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetLinkBuilder;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Builds provider-neutral password reset email content. */
@Component
public class PasswordResetEmailFactory {

    private static final String SUBJECT = "Reset your InvoWard password";
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final PasswordResetLinkBuilder linkBuilder;

    public PasswordResetEmailFactory(PasswordResetLinkBuilder linkBuilder) {
        this.linkBuilder = Objects.requireNonNull(linkBuilder);
    }

    public TransactionalEmail create(PasswordResetRequested request) {
        Objects.requireNonNull(request, "request must not be null");

        String resetUrl = linkBuilder.build(request.rawToken());
        String expiresAt = EXPIRY_FORMAT.format(request.expiresAt());
        String expiryMessage = "This password reset link expires at " + expiresAt + " (UTC).";
        String ignoreMessage = "If you did not request a password reset, you can ignore this message.";
        String textBody = "A password reset was requested for your InvoWard account.\n\n"
                + "Reset your password using this link:\n\n"
                + resetUrl + "\n\n"
                + expiryMessage + "\n\n"
                + ignoreMessage;
        String htmlBody = "<p>A password reset was requested for your InvoWard account.</p>"
                + "<p>Reset your password using this link:</p>"
                + "<p><a href=\"" + escapeHtml(resetUrl) + "\">Reset your password</a></p>"
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
