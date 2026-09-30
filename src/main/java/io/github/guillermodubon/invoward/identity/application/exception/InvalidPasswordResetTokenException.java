package io.github.guillermodubon.invoward.identity.application.exception;

/** Safe application signal for every unusable password-reset token. */
public final class InvalidPasswordResetTokenException extends RuntimeException {

    public InvalidPasswordResetTokenException() {
        super("The password reset link is invalid or expired.");
    }
}
