package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProperties;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import java.util.Properties;

/** Builds a UTF-8 MIME message and encodes it for Gmail's raw-message field. */
public final class GmailMimeMessageFactory {

    private static final String UTF_8 = StandardCharsets.UTF_8.name();

    private final String senderAddress;
    private final String senderName;

    public GmailMimeMessageFactory(EmailProperties emailProperties, GmailApiProperties gmailApiProperties) {
        Objects.requireNonNull(emailProperties, "emailProperties must not be null");
        Objects.requireNonNull(gmailApiProperties, "gmailApiProperties must not be null");
        this.senderAddress = gmailApiProperties.senderAddress();
        this.senderName = emailProperties.fromName();
    }

    public String createEncodedRawMessage(TransactionalEmail email) {
        if (email == null) {
            throw invalidMessage("Transactional email is required");
        }

        InternetAddress sender = parseSingleMailbox(
                senderAddress,
                EmailDeliveryFailure.CONFIGURATION,
                "Configured email sender address is invalid");
        InternetAddress recipient = parseSingleMailbox(
                email.recipient(),
                EmailDeliveryFailure.INVALID_MESSAGE,
                "Transactional email recipient address is invalid");
        validateHeaderValue(senderName, EmailDeliveryFailure.CONFIGURATION,
                "Configured email sender name contains invalid header characters");
        validateHeaderValue(email.subject(), EmailDeliveryFailure.INVALID_MESSAGE,
                "Transactional email subject contains invalid header characters");

        try {
            if (senderName != null && !senderName.isBlank()) {
                sender.setPersonal(senderName, UTF_8);
            }

            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(sender);
            message.setRecipient(Message.RecipientType.TO, recipient);
            message.setSubject(email.subject(), UTF_8);
            message.setSentDate(new Date());
            setBody(message, email);
            message.saveChanges();

            ByteArrayOutputStream messageBytes = new ByteArrayOutputStream();
            message.writeTo(messageBytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(messageBytes.toByteArray());
        } catch (MessagingException | IOException exception) {
            throw new EmailDeliveryException(
                    EmailDeliveryFailure.INVALID_MESSAGE,
                    "Transactional email could not be encoded as MIME",
                    exception);
        }
    }

    private static InternetAddress parseSingleMailbox(
            String value,
            EmailDeliveryFailure failure,
            String safeMessage) {
        validateHeaderValue(value, failure, safeMessage);

        try {
            String candidate = value.strip();
            InternetAddress[] addresses = InternetAddress.parse(candidate, true);
            if (addresses.length != 1 || addresses[0].isGroup() || addresses[0].getPersonal() != null) {
                throw new EmailDeliveryException(failure, safeMessage);
            }
            addresses[0].validate();
            if (!candidate.equals(addresses[0].getAddress())) {
                throw new EmailDeliveryException(failure, safeMessage);
            }
            return addresses[0];
        } catch (AddressException exception) {
            throw new EmailDeliveryException(failure, safeMessage);
        }
    }

    private static void validateHeaderValue(String value, EmailDeliveryFailure failure, String safeMessage) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new EmailDeliveryException(failure, safeMessage);
        }
    }

    private static void setBody(MimeMessage message, TransactionalEmail email) throws MessagingException {
        if (email.htmlBody() == null) {
            message.setText(email.textBody(), UTF_8);
            return;
        }

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(email.textBody(), UTF_8);

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setText(email.htmlBody(), UTF_8, "html");

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(htmlPart);
        message.setContent(alternative);
    }

    private static EmailDeliveryException invalidMessage(String safeMessage) {
        return new EmailDeliveryException(EmailDeliveryFailure.INVALID_MESSAGE, safeMessage);
    }
}
