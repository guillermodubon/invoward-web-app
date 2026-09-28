package io.github.guillermodubon.invoward.notification.application.exception;

/** Provider-neutral classification of a failed email submission. */
public enum EmailDeliveryFailure {
    INVALID_MESSAGE(false),
    AUTHENTICATION(false),
    AUTHORIZATION(false),
    RATE_LIMITED(true),
    PROVIDER_UNAVAILABLE(true),
    TIMEOUT(true),
    CONFIGURATION(false),
    UNKNOWN(false);

    private final boolean retryable;

    EmailDeliveryFailure(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
