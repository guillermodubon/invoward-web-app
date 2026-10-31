package io.github.guillermodubon.invoward.sharing.application.exception;

/** Signals that a share-link listing page is outside the supported bounds. */
public final class InvalidShareLinkQueryException extends RuntimeException {

    public InvalidShareLinkQueryException() {
        super("The share-link query is invalid.");
    }
}
