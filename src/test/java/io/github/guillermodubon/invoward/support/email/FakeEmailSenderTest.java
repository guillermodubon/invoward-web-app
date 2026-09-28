package io.github.guillermodubon.invoward.support.email;

import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.EmailDeliveryReceipt;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeEmailSenderTest {

    private final FakeEmailSender sender = new FakeEmailSender();

    @Test
    void capturesSuccessfullySentMessagesInOrder() {
        TransactionalEmail first = email("first@example.com", "First");
        TransactionalEmail second = email("second@example.com", "Second");

        sender.send(first);
        sender.send(second);

        assertEquals(List.of(first, second), sender.sentEmails());
    }

    @Test
    void generatesDeterministicMessageIdsAndResetsSequenceWhenCleared() {
        assertEquals("fake-email-1", sender.send(email("one@example.com", "One")).providerMessageId());
        assertEquals("fake-email-2", sender.send(email("two@example.com", "Two")).providerMessageId());

        sender.clear();

        assertTrue(sender.sentEmails().isEmpty());
        assertEquals("fake-email-1", sender.send(email("three@example.com", "Three")).providerMessageId());
    }

    @Test
    void configuredFailureIsClassifiedAndDoesNotCaptureOrConsumeAnId() {
        sender.configureFailure(EmailDeliveryFailure.RATE_LIMITED);

        EmailDeliveryException failure = assertThrows(
                EmailDeliveryException.class,
                () -> sender.send(email("recipient@example.com", "Subject")));

        assertEquals(EmailDeliveryFailure.RATE_LIMITED, failure.failure());
        assertTrue(failure.retryable());
        assertTrue(sender.sentEmails().isEmpty());

        sender.clearFailure();
        EmailDeliveryReceipt receipt = sender.send(email("recipient@example.com", "Subject"));
        assertEquals("fake-email-1", receipt.providerMessageId());
    }

    @Test
    void capturesConcurrentSendsWithoutLosingMessagesOrIds() {
        int messageCount = 32;
        TransactionalEmail message = email("recipient@example.com", "Subject");
        Set<String> actualMessageIds = ConcurrentHashMap.newKeySet();

        IntStream.range(0, messageCount).parallel()
                .forEach(ignored -> actualMessageIds.add(sender.send(message).providerMessageId()));

        Set<String> expectedMessageIds = IntStream.range(0, messageCount)
                .mapToObj(index -> "fake-email-" + (index + 1))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(messageCount, sender.sentEmails().size());
        assertEquals(expectedMessageIds, actualMessageIds);
    }

    private static TransactionalEmail email(String recipient, String subject) {
        return new TransactionalEmail(recipient, subject, "Plain text body", "<p>HTML body</p>");
    }
}
