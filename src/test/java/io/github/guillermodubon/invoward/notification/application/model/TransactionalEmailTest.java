package io.github.guillermodubon.invoward.notification.application.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionalEmailTest {

    @Test
    void acceptsTextEmailAndTrimsHeaderValues() {
        TransactionalEmail email = new TransactionalEmail(
                "  recipient@example.com  ", "  Invoice ready  ", "Your invoice is ready.", null);

        assertEquals("recipient@example.com", email.recipient());
        assertEquals("Invoice ready", email.subject());
        assertEquals("Your invoice is ready.", email.textBody());
        assertNull(email.htmlBody());
    }

    @Test
    void acceptsTextAndHtmlEmail() {
        TransactionalEmail email = new TransactionalEmail(
                "recipient@example.com", "Invoice ready", "Your invoice is ready.", "<p>Invoice ready</p>");

        assertEquals("<p>Invoice ready</p>", email.htmlBody());
    }

    @Test
    void rejectsBlankRecipient() {
        assertInvalidEmail(" ", "Invoice", "Body", null);
    }

    @Test
    void rejectsRecipientHeaderInjectionCharacters() {
        assertInvalidEmail("recipient\r@example.com", "Invoice", "Body", null);
        assertInvalidEmail("recipient\n@example.com", "Invoice", "Body", null);
        assertInvalidEmail("recipient\0@example.com", "Invoice", "Body", null);
    }

    @Test
    void rejectsOverlongRecipient() {
        assertInvalidEmail("a".repeat(321), "Invoice", "Body", null);
    }

    @Test
    void acceptsMaximumLengthRecipientAfterTrimming() {
        String recipient = "a".repeat(320);

        assertEquals(recipient, new TransactionalEmail(" " + recipient + " ", "Invoice", "Body", null).recipient());
    }

    @Test
    void rejectsBlankSubject() {
        assertInvalidEmail("recipient@example.com", "  ", "Body", null);
    }

    @Test
    void rejectsSubjectHeaderInjectionCharacters() {
        assertInvalidEmail("recipient@example.com", "Invoice\rNotice", "Body", null);
        assertInvalidEmail("recipient@example.com", "Invoice\nNotice", "Body", null);
        assertInvalidEmail("recipient@example.com", "Invoice\0Notice", "Body", null);
    }

    @Test
    void rejectsOverlongSubject() {
        assertInvalidEmail("recipient@example.com", "s".repeat(201), "Body", null);
    }

    @Test
    void rejectsBlankTextBody() {
        assertInvalidEmail("recipient@example.com", "Invoice", " \t\n", null);
    }

    @Test
    void rejectsOversizedTextBody() {
        assertInvalidEmail("recipient@example.com", "Invoice", "x".repeat(100_001), null);
    }

    @Test
    void acceptsMaximumLengthTextBody() {
        assertEquals(100_000, new TransactionalEmail(
                "recipient@example.com", "Invoice", "x".repeat(100_000), null).textBody().length());
    }

    @Test
    void rejectsBlankProvidedHtmlBody() {
        assertInvalidEmail("recipient@example.com", "Invoice", "Body", " \t\n");
    }

    @Test
    void rejectsOversizedHtmlBody() {
        assertInvalidEmail("recipient@example.com", "Invoice", "Body", "x".repeat(100_001));
    }

    @Test
    void acceptsMaximumLengthHtmlBody() {
        assertEquals(100_000, new TransactionalEmail(
                "recipient@example.com", "Invoice", "Body", "x".repeat(100_000)).htmlBody().length());
    }

    @Test
    void rejectsNullRequiredFields() {
        assertInvalidEmail(null, "Invoice", "Body", null);
        assertInvalidEmail("recipient@example.com", null, "Body", null);
        assertInvalidEmail("recipient@example.com", "Invoice", null, null);
    }

    private static void assertInvalidEmail(String recipient, String subject, String textBody, String htmlBody) {
        assertThrows(IllegalArgumentException.class,
                () -> new TransactionalEmail(recipient, subject, textBody, htmlBody));
    }
}
