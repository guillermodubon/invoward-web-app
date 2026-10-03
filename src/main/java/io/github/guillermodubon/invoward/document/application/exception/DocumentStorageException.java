package io.github.guillermodubon.invoward.document.application.exception;

import java.util.Objects;

/** Provider-neutral storage failure that does not disclose keys, URLs, or provider details. */
public final class DocumentStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Failure failure;

    public DocumentStorageException(Failure failure) {
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
            case NOT_FOUND -> "Stored document was not found";
            case UNAVAILABLE -> "Document storage is unavailable";
            case PERMISSION_DENIED -> "Document storage access is denied";
            case INVALID_CONFIGURATION -> "Document storage is not configured correctly";
            case UNKNOWN -> "Document storage operation failed";
        };
    }

    public enum Failure {
        NOT_FOUND,
        UNAVAILABLE,
        PERMISSION_DENIED,
        INVALID_CONFIGURATION,
        UNKNOWN
    }
}
