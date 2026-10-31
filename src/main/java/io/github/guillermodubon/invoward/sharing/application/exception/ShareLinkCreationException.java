package io.github.guillermodubon.invoward.sharing.application.exception;

/** Safe failure after bounded share-link creation attempts are exhausted. */
public final class ShareLinkCreationException extends RuntimeException {

    public ShareLinkCreationException() {
        super("Share link could not be created.");
    }
}
