package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProperties;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProvider;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmailMimeMessageFactoryTest {

    private static final String SENDER_ADDRESS = "billing@example.com";
    private static final String SENDER_NAME = "InvoWard Billing";

    @Test
    void buildsMimeHeadersWithTrustedSenderAndRequestedRecipient() throws Exception {
        MimeMessage message = createMimeMessage(factory(SENDER_ADDRESS, SENDER_NAME), validEmail());

        InternetAddress from = assertInstanceOf(InternetAddress.class, message.getFrom()[0]);
        InternetAddress to = assertInstanceOf(InternetAddress.class,
                message.getRecipients(Message.RecipientType.TO)[0]);
        assertEquals(SENDER_ADDRESS, from.getAddress());
        assertEquals(SENDER_NAME, from.getPersonal());
        assertEquals("recipient@example.com", to.getAddress());
        assertEquals("Invoice ready", message.getSubject());
        assertNotNull(message.getSentDate());
        assertNotNull(message.getHeader("MIME-Version"));
    }

    @Test
    void encodesUnicodeSenderNameAndSubjectSafely() throws Exception {
        String senderName = "InvoWard Facturación";
        String subject = "Factura recibida — revisión ✓";
        String encoded = factory(SENDER_ADDRESS, senderName).createEncodedRawMessage(
                new TransactionalEmail("recipient@example.com", subject, "Body", null));
        MimeMessage message = parse(encoded);

        InternetAddress from = assertInstanceOf(InternetAddress.class, message.getFrom()[0]);
        assertEquals(senderName, from.getPersonal());
        assertEquals(subject, message.getSubject());
    }

    @Test
    void preservesNonAsciiBodyText() throws Exception {
        String body = "Revisión de factura: café, número 2 y total €125.50 — ✓";
        MimeMessage message = createMimeMessage(factory(SENDER_ADDRESS, SENDER_NAME),
                new TransactionalEmail("recipient@example.com", "Revisión", body, null));

        assertEquals(body, message.getContent());
    }

    @Test
    void createsUtf8PlainTextMessageWhenHtmlIsAbsent() throws Exception {
        MimeMessage message = createMimeMessage(factory(SENDER_ADDRESS, SENDER_NAME), validEmail());
        ContentType contentType = new ContentType(message.getContentType());

        assertTrue(contentType.match("text/plain"));
        assertEquals("UTF-8", contentType.getParameter("charset").toUpperCase(Locale.ROOT));
        assertEquals("Your invoice is ready.", message.getContent());
    }

    @Test
    void createsTextThenHtmlMultipartAlternativeWithUtf8Parts() throws Exception {
        String text = "La factura está lista.";
        String html = "<p>La factura está lista.</p>";
        MimeMessage message = createMimeMessage(factory(SENDER_ADDRESS, SENDER_NAME),
                new TransactionalEmail("recipient@example.com", "Invoice ready", text, html));
        ContentType contentType = new ContentType(message.getContentType());
        MimeMultipart multipart = assertInstanceOf(MimeMultipart.class, message.getContent());

        assertTrue(contentType.match("multipart/alternative"));
        assertEquals(2, multipart.getCount());
        assertPart(multipart.getBodyPart(0), "text/plain", text);
        assertPart(multipart.getBodyPart(1), "text/html", html);
    }

    @Test
    void encodesRawMessageUsingUnpaddedUrlSafeBase64() throws Exception {
        String encoded = factory(SENDER_ADDRESS, SENDER_NAME).createEncodedRawMessage(validEmail());

        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("/"));
        assertFalse(encoded.contains("="));
        assertNotNull(parse(encoded).getSubject());
    }

    @Test
    void senderCannotBeOverriddenByTransactionalEmailCallers() throws Exception {
        TransactionalEmail email = validEmail();
        MimeMessage message = createMimeMessage(factory(SENDER_ADDRESS, SENDER_NAME), email);
        InternetAddress from = assertInstanceOf(InternetAddress.class, message.getFrom()[0]);

        assertEquals(SENDER_ADDRESS, from.getAddress());
        assertEquals(4, TransactionalEmail.class.getRecordComponents().length);
    }

    @Test
    void rejectsMalformedRecipientAsInvalidMessage() {
        EmailDeliveryException exception = assertThrows(EmailDeliveryException.class,
                () -> factory(SENDER_ADDRESS, SENDER_NAME).createEncodedRawMessage(
                        new TransactionalEmail("not-an-address", "Invoice", "Body", null)));

        assertEquals(EmailDeliveryFailure.INVALID_MESSAGE, exception.failure());
        assertFalse(exception.safeMessage().contains("not-an-address"));
    }

    @Test
    void rejectsMalformedConfiguredSenderAsConfigurationFailure() {
        EmailDeliveryException exception = assertThrows(EmailDeliveryException.class,
                () -> factory("not-an-address", SENDER_NAME).createEncodedRawMessage(validEmail()));

        assertEquals(EmailDeliveryFailure.CONFIGURATION, exception.failure());
        assertFalse(exception.safeMessage().contains("not-an-address"));
    }

    @Test
    void rejectsHeaderInjectionInUserControlledHeadersBeforeMimeCreation() {
        for (String control : new String[]{"\r", "\n", "\0"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TransactionalEmail("recipient" + control + "@example.com", "Invoice", "Body", null));
            assertThrows(IllegalArgumentException.class,
                    () -> new TransactionalEmail("recipient@example.com", "Invoice" + control, "Body", null));
        }
    }

    @Test
    void rejectsHeaderInjectionInConfiguredSenderAddressAndName() {
        for (String control : new String[]{"\r", "\n", "\0"}) {
            EmailDeliveryException senderFailure = assertThrows(EmailDeliveryException.class,
                    () -> factory(SENDER_ADDRESS + control, SENDER_NAME).createEncodedRawMessage(validEmail()));
            EmailDeliveryException nameFailure = assertThrows(EmailDeliveryException.class,
                    () -> factory(SENDER_ADDRESS, SENDER_NAME + control).createEncodedRawMessage(validEmail()));

            assertEquals(EmailDeliveryFailure.CONFIGURATION, senderFailure.failure());
            assertEquals(EmailDeliveryFailure.CONFIGURATION, nameFailure.failure());
        }
    }

    @Test
    void doesNotAddAttachmentsOrDisallowedHeaders() throws Exception {
        MimeMessage message = createMimeMessage(factory(SENDER_ADDRESS, SENDER_NAME),
                new TransactionalEmail("recipient@example.com", "Invoice", "Plain body", "<p>HTML body</p>"));

        assertNull(message.getHeader("Cc"));
        assertNull(message.getHeader("Bcc"));
        assertNull(message.getHeader("Reply-To"));
        assertNoCustomHeaders(message.getAllHeaders());

        MimeMultipart multipart = assertInstanceOf(MimeMultipart.class, message.getContent());
        for (int index = 0; index < multipart.getCount(); index++) {
            assertFalse(Part.ATTACHMENT.equalsIgnoreCase(multipart.getBodyPart(index).getDisposition()));
        }
    }

    private static void assertPart(BodyPart part, String expectedType, String expectedContent) throws Exception {
        ContentType contentType = new ContentType(part.getContentType());
        assertTrue(contentType.match(expectedType));
        assertEquals("UTF-8", contentType.getParameter("charset").toUpperCase(Locale.ROOT));
        assertEquals(expectedContent, part.getContent());
    }

    private static void assertNoCustomHeaders(Enumeration<?> headers) throws Exception {
        while (headers.hasMoreElements()) {
            String headerName = ((jakarta.mail.Header) headers.nextElement()).getName();
            assertFalse(headerName.toLowerCase(Locale.ROOT).startsWith("x-"));
        }
    }

    private static GmailMimeMessageFactory factory(String senderAddress, String senderName) {
        EmailProperties emailProperties = new EmailProperties(EmailProvider.GMAIL, senderName);
        GmailApiProperties gmailApiProperties = new GmailApiProperties(
                "fake-client-id", "fake-client-secret", "fake-refresh-token", senderAddress,
                Duration.ofSeconds(5), Duration.ofSeconds(20));
        return new GmailMimeMessageFactory(emailProperties, gmailApiProperties);
    }

    private static TransactionalEmail validEmail() {
        return new TransactionalEmail(
                "recipient@example.com", "Invoice ready", "Your invoice is ready.", null);
    }

    private static MimeMessage createMimeMessage(GmailMimeMessageFactory factory, TransactionalEmail email)
            throws Exception {
        return parse(factory.createEncodedRawMessage(email));
    }

    private static MimeMessage parse(String encodedRawMessage) throws Exception {
        byte[] messageBytes = Base64.getUrlDecoder().decode(encodedRawMessage);
        return new MimeMessage(
                Session.getInstance(new Properties()), new ByteArrayInputStream(messageBytes));
    }
}
