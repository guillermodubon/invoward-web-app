package io.github.guillermodubon.invoward.sharing.application.exception;

/** Signals that the Analysis already has the configured maximum active share links. */
public final class ShareLinkLimitReachedException extends RuntimeException {

    public ShareLinkLimitReachedException() {
        super("The active share-link limit has been reached.");
    }
}
