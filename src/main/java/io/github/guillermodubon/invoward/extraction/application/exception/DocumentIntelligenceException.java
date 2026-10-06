package io.github.guillermodubon.invoward.extraction.application.exception;

import java.util.Objects;

/** Safe, provider-neutral failure from document classification or extraction. */
public final class DocumentIntelligenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Failure failure;

    public DocumentIntelligenceException(Failure failure) {
        super(messageFor(failure));
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public Failure failure() {
        return failure;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[failure=" + failure + "]";
    }

    private static String messageFor(Failure failure) {
        return switch (Objects.requireNonNull(failure, "failure must not be null")) {
            case UNAVAILABLE -> "Document intelligence is unavailable";
            case RATE_LIMITED -> "Document intelligence request was rate limited";
            case AUTHENTICATION -> "Document intelligence is not configured correctly";
            case INVALID_RESPONSE -> "Document intelligence returned an invalid response";
            case UNKNOWN -> "Document intelligence operation failed";
        };
    }

    public enum Failure {
        UNAVAILABLE,
        RATE_LIMITED,
        AUTHENTICATION,
        INVALID_RESPONSE,
        UNKNOWN
    }
}
