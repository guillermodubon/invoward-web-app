package io.github.guillermodubon.invoward.sharing.application.exception;

/** Signals that the owner-visible Analysis is not ready for sharing. */
public final class ShareLinkNotShareableException extends RuntimeException {

    public ShareLinkNotShareableException() {
        super("Analysis is not available for sharing.");
    }
}
