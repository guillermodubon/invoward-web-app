package io.github.guillermodubon.invoward.sharing.application.exception;

/** Safe signal that a share link is absent from the already-authorized parent Analysis. */
public final class ShareLinkNotFoundException extends RuntimeException {

    public ShareLinkNotFoundException() {
        super("Share link was not found.");
    }
}
