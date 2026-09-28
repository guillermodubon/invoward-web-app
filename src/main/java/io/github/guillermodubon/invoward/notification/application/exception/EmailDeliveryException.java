package io.github.guillermodubon.invoward.notification.application.exception;

import java.util.Objects;

/** Safe application-level failure raised when a transactional email cannot be submitted. */
public final class EmailDeliveryException extends RuntimeException {

    private final EmailDeliveryFailure failure;
    private final String safeMessage;
    private final boolean retryable;

    public EmailDeliveryException(EmailDeliveryFailure failure, String safeMessage) {
        this(failure, safeMessage, null);
    }

    public EmailDeliveryException(EmailDeliveryFailure failure, String safeMessage, Throwable cause) {
        super(requireSafeMessage(safeMessage), cause);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
        this.safeMessage = safeMessage.strip();
        this.retryable = failure.isRetryable();
    }

    public EmailDeliveryFailure failure() {
        return failure;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public boolean retryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[failure=" + failure + ", retryable=" + retryable + "]";
    }

    private static String requireSafeMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return message.strip();
    }
}
