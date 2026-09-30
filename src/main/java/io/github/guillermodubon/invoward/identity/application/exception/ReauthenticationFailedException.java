package io.github.guillermodubon.invoward.identity.application.exception;

/** Safe signal that freshly supplied credentials could not be verified. */
public final class ReauthenticationFailedException extends RuntimeException {

    public ReauthenticationFailedException() {
        super("The current password could not be verified.");
    }
}
