package io.github.guillermodubon.invoward.identity.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.guillermodubon.invoward.identity.application.event.PasswordResetRequested;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.support.email.FakeEmailSender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordResetEmailListenerTest {

    private static final String RECIPIENT = "private-recipient@example.test";
    private static final String RAW_TOKEN = "private-reset-token-secret";
    private static final String RESET_URL = "https://app.example/reset-password";

    @Test
    void sendsOnePasswordResetEmailForTheEvent() {
        FakeEmailSender sender = new FakeEmailSender();
        PasswordResetEmailListener listener = listener(sender);

        listener.onPasswordResetRequested(event());

        assertEquals(1, sender.sentEmails().size());
        assertEquals(RECIPIENT, sender.sentEmails().getFirst().recipient());
        assertEquals("Reset your InvoWard password", sender.sentEmails().getFirst().subject());
        assertTrue(sender.sentEmails().getFirst().textBody().contains(RAW_TOKEN));
    }

    @Test
    void providerFailureIsSwallowedAndLogsOnlySafeFailureClassification() {
        FakeEmailSender sender = new FakeEmailSender();
        sender.configureFailure(EmailDeliveryFailure.PROVIDER_UNAVAILABLE);
        PasswordResetEmailListener listener = listener(sender);

        Logger logger = (Logger) LoggerFactory.getLogger(PasswordResetEmailListener.class);
        Level originalLevel = logger.getLevel();
        boolean originalAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            listener.onPasswordResetRequested(event());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
            logger.setAdditive(originalAdditive);
        }

        String capturedLogs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertEquals(1, appender.list.size());
        assertTrue(capturedLogs.contains(
                "operation=password_reset_requested result=failure failureType=PROVIDER_UNAVAILABLE"));
        for (String sensitiveValue : List.of(
                RECIPIENT,
                RAW_TOKEN,
                RESET_URL,
                "Reset your InvoWard password",
                "If you did not request a password reset")) {
            assertFalse(capturedLogs.contains(sensitiveValue));
        }
        assertTrue(sender.sentEmails().isEmpty());
    }

    private static PasswordResetEmailListener listener(FakeEmailSender sender) {
        return new PasswordResetEmailListener(
                new PasswordResetEmailFactory(token -> RESET_URL + "?token=" + token), sender);
    }

    private static PasswordResetRequested event() {
        return new PasswordResetRequested(
                RECIPIENT, RAW_TOKEN, Instant.parse("2026-09-29T18:30:00Z"));
    }
}
