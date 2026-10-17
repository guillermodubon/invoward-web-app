package io.github.guillermodubon.invoward.reporting.application.exception;

import java.util.Objects;

/** Provider-neutral report storage failure that does not disclose storage details. */
public final class ReportStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Failure failure;

    public ReportStorageException(Failure failure) {
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
            case NOT_FOUND -> "Stored report was not found";
            case UNAVAILABLE -> "Report storage is unavailable";
            case PERMISSION_DENIED -> "Report storage access is denied";
            case INVALID_CONFIGURATION -> "Report storage is not configured correctly";
            case UNKNOWN -> "Report storage operation failed";
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
