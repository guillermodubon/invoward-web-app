package io.github.guillermodubon.invoward.identity.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.guillermodubon.invoward.identity.application.event.EmailChangeVerificationRequested;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.support.email.FakeEmailSender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmailChangeVerificationEmailListenerTest {

    private static final String RECIPIENT = "private-recipient@example.test";
    private static final String RAW_TOKEN = "private-email-change-token-secret";

    @Test
    void sendsConfirmationToRequestedNewAddress() {
        FakeEmailSender sender = new FakeEmailSender();
        EmailChangeVerificationEmailListener listener = listener(sender);

        listener.onEmailChangeVerificationRequested(event());

        assertEquals(1, sender.sentEmails().size());
        assertEquals(RECIPIENT, sender.sentEmails().getFirst().recipient());
        assertEquals("Confirm your new InvoWard email", sender.sentEmails().getFirst().subject());
        assertFalse(sender.sentEmails().getFirst().textBody().isBlank());
    }

    @Test
    void providerFailureIsSwallowedAndLogsNoSensitiveEmailOrToken() {
        FakeEmailSender sender = new FakeEmailSender();
        sender.configureFailure(EmailDeliveryFailure.PROVIDER_UNAVAILABLE);
        EmailChangeVerificationEmailListener listener = listener(sender);
        Logger logger = (Logger) LoggerFactory.getLogger(EmailChangeVerificationEmailListener.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            listener.onEmailChangeVerificationRequested(event());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        String logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining(" "));
        assertFalse(logged.contains(RECIPIENT));
        assertFalse(logged.contains(RAW_TOKEN));
        assertEquals(1, appender.list.size());
        assertEquals("operation=email_change_requested result=failure failureType=PROVIDER_UNAVAILABLE", logged);
    }

    private static EmailChangeVerificationEmailListener listener(FakeEmailSender sender) {
        return new EmailChangeVerificationEmailListener(
                new EmailChangeVerificationEmailFactory(token -> "https://app.example/?token=" + token), sender);
    }

    private static EmailChangeVerificationRequested event() {
        return new EmailChangeVerificationRequested(
                RECIPIENT, RAW_TOKEN, Instant.parse("2026-09-30T18:30:00Z"));
    }
}
