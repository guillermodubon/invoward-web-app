package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.EmailDeliveryReceipt;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Connects the provider-neutral email port to Gmail MIME creation and message dispatch. */
public final class GmailApiEmailAdapter implements EmailSender {

    private static final String OPERATION = "transactional_email_send";
    private static final String PROVIDER = "gmail";
    private static final Logger LOGGER = LoggerFactory.getLogger(GmailApiEmailAdapter.class);

    private final GmailMimeMessageFactory mimeMessageFactory;
    private final GmailMessageDispatcher messageDispatcher;

    public GmailApiEmailAdapter(
            GmailMimeMessageFactory mimeMessageFactory,
            GmailMessageDispatcher messageDispatcher) {
        this.mimeMessageFactory = Objects.requireNonNull(
                mimeMessageFactory, "mimeMessageFactory must not be null");
        this.messageDispatcher = Objects.requireNonNull(
                messageDispatcher, "messageDispatcher must not be null");
    }

    @Override
    public EmailDeliveryReceipt send(TransactionalEmail email) {
        long startedAtNanos = System.nanoTime();
        try {
            String rawMessage = mimeMessageFactory.createEncodedRawMessage(email);
            String providerMessageId;
            try {
                providerMessageId = messageDispatcher.dispatch(rawMessage);
            } catch (GmailMessageDispatcher.DispatchException exception) {
                throw new EmailDeliveryException(exception.failure(), safeMessage(exception.failure()));
            }

            EmailDeliveryReceipt receipt = new EmailDeliveryReceipt(providerMessageId);
            LOGGER.info("operation={} provider={} result=success durationMs={} providerMessageId={}",
                    OPERATION, PROVIDER, elapsedMilliseconds(startedAtNanos), receipt.providerMessageId());
            return receipt;
        } catch (EmailDeliveryException exception) {
            LOGGER.warn("operation={} provider={} result=failure failureType={} durationMs={}",
                    OPERATION, PROVIDER, exception.failure(), elapsedMilliseconds(startedAtNanos));
            throw exception;
        }
    }

    private static long elapsedMilliseconds(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static String safeMessage(EmailDeliveryFailure failure) {
        return switch (failure) {
            case INVALID_MESSAGE -> "Email message could not be accepted by the provider";
            case AUTHENTICATION -> "Email provider authentication failed";
            case AUTHORIZATION -> "Email provider authorization failed";
            case RATE_LIMITED -> "Email provider rate limit reached";
            case PROVIDER_UNAVAILABLE -> "Email provider is temporarily unavailable";
            case TIMEOUT -> "Email provider request timed out";
            case CONFIGURATION -> "Email provider configuration is invalid";
            case UNKNOWN -> "Email delivery failed for an unexpected reason";
        };
    }
}
