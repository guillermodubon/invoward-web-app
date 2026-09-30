package io.github.guillermodubon.invoward.identity.application.exception;

/** Safe application signal for any unusable registration-verification token. */
public final class InvalidVerificationTokenException extends RuntimeException {

    public InvalidVerificationTokenException() {
        super("The verification link is invalid or expired.");
    }
}
