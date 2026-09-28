package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.EmailDeliveryReceipt;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProperties;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GmailApiEmailAdapterTest {

    private static final String RAW_MESSAGE = "encoded-gmail-message";
    private static final String PROVIDER_MESSAGE_ID = "provider-message-id";

    @Test
    void sendsOneEncodedMessageAndReturnsProviderNeutralReceipt() {
        GmailMimeMessageFactory mimeFactory = mock(GmailMimeMessageFactory.class);
        GmailMessageDispatcher dispatcher = mock(GmailMessageDispatcher.class);
        TransactionalEmail email = validEmail();
        when(mimeFactory.createEncodedRawMessage(email)).thenReturn(RAW_MESSAGE);
        when(dispatcher.dispatch(RAW_MESSAGE)).thenReturn(PROVIDER_MESSAGE_ID);
        GmailApiEmailAdapter adapter = new GmailApiEmailAdapter(mimeFactory, dispatcher);

        EmailDeliveryReceipt receipt = adapter.send(email);

        assertEquals(PROVIDER_MESSAGE_ID, receipt.providerMessageId());
        verify(mimeFactory, times(1)).createEncodedRawMessage(email);
        verify(dispatcher, times(1)).dispatch(RAW_MESSAGE);
    }

    @Test
    void invalidRecipientNeverReachesDispatcher() {
        GmailMessageDispatcher dispatcher = mock(GmailMessageDispatcher.class);
        GmailApiEmailAdapter adapter = new GmailApiEmailAdapter(
                mimeMessageFactory(), dispatcher);
        TransactionalEmail email = new TransactionalEmail("not-an-address", "Invoice", "Invoice body", null);

        EmailDeliveryException exception = assertThrows(EmailDeliveryException.class, () -> adapter.send(email));

        assertEquals(EmailDeliveryFailure.INVALID_MESSAGE, exception.failure());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void mapsDispatcherFailuresToSafeApplicationExceptions() {
        for (EmailDeliveryFailure failure : List.of(
                EmailDeliveryFailure.INVALID_MESSAGE,
                EmailDeliveryFailure.AUTHENTICATION,
                EmailDeliveryFailure.AUTHORIZATION,
                EmailDeliveryFailure.RATE_LIMITED,
                EmailDeliveryFailure.PROVIDER_UNAVAILABLE,
                EmailDeliveryFailure.TIMEOUT,
                EmailDeliveryFailure.UNKNOWN)) {
            GmailMimeMessageFactory mimeFactory = mock(GmailMimeMessageFactory.class);
            GmailMessageDispatcher dispatcher = mock(GmailMessageDispatcher.class);
            GmailMessageDispatcher.DispatchException dispatchFailure =
                    mock(GmailMessageDispatcher.DispatchException.class);
            when(mimeFactory.createEncodedRawMessage(any(TransactionalEmail.class))).thenReturn(RAW_MESSAGE);
            when(dispatchFailure.failure()).thenReturn(failure);
            when(dispatcher.dispatch(RAW_MESSAGE)).thenThrow(dispatchFailure);
            GmailApiEmailAdapter adapter = new GmailApiEmailAdapter(mimeFactory, dispatcher);

            EmailDeliveryException exception = assertThrows(
                    EmailDeliveryException.class, () -> adapter.send(validEmail()));

            assertEquals(failure, exception.failure());
            assertEquals(failure.isRetryable(), exception.retryable());
            assertNotNull(exception.safeMessage());
            assertFalse(exception.safeMessage().contains(RAW_MESSAGE));
            assertNull(exception.getCause());
            verify(dispatcher, times(1)).dispatch(RAW_MESSAGE);
        }
    }

    @Test
    void logsOnlyApprovedMetadataForSuccessAndFailure() {
        String rawMessage = "raw-gmail-message-secret-marker";
        String providerResponse = "raw-provider-response-secret-marker";
        String token = "fake-refresh-token-secret-marker";
        TransactionalEmail email = new TransactionalEmail(
                "private-recipient@example.test",
                "private-subject-marker",
                "private-text-body-marker",
                "<p>private-html-body-marker</p>");
        GmailMimeMessageFactory mimeFactory = mock(GmailMimeMessageFactory.class);
        GmailMessageDispatcher dispatcher = mock(GmailMessageDispatcher.class);
        GmailMessageDispatcher.DispatchException dispatchFailure =
                mock(GmailMessageDispatcher.DispatchException.class);
        when(mimeFactory.createEncodedRawMessage(email)).thenReturn(rawMessage);
        when(dispatchFailure.failure()).thenReturn(EmailDeliveryFailure.TIMEOUT);
        when(dispatchFailure.getMessage()).thenReturn(providerResponse);
        when(dispatcher.dispatch(rawMessage)).thenReturn(PROVIDER_MESSAGE_ID).thenThrow(dispatchFailure);
        GmailApiEmailAdapter adapter = new GmailApiEmailAdapter(mimeFactory, dispatcher);

        Logger logger = (Logger) LoggerFactory.getLogger(GmailApiEmailAdapter.class);
        Level originalLevel = logger.getLevel();
        boolean originalAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            adapter.send(email);
            assertThrows(EmailDeliveryException.class, () -> adapter.send(email));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
            logger.setAdditive(originalAdditive);
        }

        String capturedLogs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        assertEquals(2, appender.list.size());
        assertTrue(capturedLogs.contains("operation=transactional_email_send provider=gmail result=success"));
        assertTrue(capturedLogs.contains("durationMs="));
        assertTrue(capturedLogs.contains("providerMessageId=" + PROVIDER_MESSAGE_ID));
        assertTrue(capturedLogs.contains("result=failure failureType=TIMEOUT"));
        for (String sensitiveValue : List.of(
                email.recipient(), email.subject(), email.textBody(), email.htmlBody(),
                rawMessage, providerResponse, token)) {
            assertFalse(capturedLogs.contains(sensitiveValue));
        }
    }

    private static GmailMimeMessageFactory mimeMessageFactory() {
        return new GmailMimeMessageFactory(
                new EmailProperties(EmailProvider.GMAIL, "InvoWard"),
                new GmailApiProperties(
                        "fake-client-id", "fake-client-secret", "fake-refresh-token", "sender@example.com",
                        Duration.ofSeconds(5), Duration.ofSeconds(20)));
    }

    private static TransactionalEmail validEmail() {
        return new TransactionalEmail(
                "recipient@example.com", "Invoice ready", "Your invoice is ready.", null);
    }
}
