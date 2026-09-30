package io.github.guillermodubon.invoward.identity.application.exception;

/** Signals that an email address became unavailable during a persistence update. */
public final class EmailAddressConflictException extends RuntimeException {

    public EmailAddressConflictException() {
        super("The email address is unavailable.");
    }
}
