package io.github.guillermodubon.invoward.identity.application.exception;

/** Safe application signal that an account snapshot was changed by another operation. */
public final class AccountConflictException extends RuntimeException {

    public AccountConflictException() {
        super("The account changed while the operation was in progress");
    }
}
