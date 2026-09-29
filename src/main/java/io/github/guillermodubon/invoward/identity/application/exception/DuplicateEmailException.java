package io.github.guillermodubon.invoward.identity.application.exception;

/** Safe application signal for a registration uniqueness race. */
public final class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("Account creation failed");
    }
}
