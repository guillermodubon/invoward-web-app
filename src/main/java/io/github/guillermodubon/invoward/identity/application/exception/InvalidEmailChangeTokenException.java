package io.github.guillermodubon.invoward.identity.application.exception;

/** Safe application signal for any unusable email-change confirmation token. */
public final class InvalidEmailChangeTokenException extends RuntimeException {

    public InvalidEmailChangeTokenException() {
        super("The email change could not be completed.");
    }
}
