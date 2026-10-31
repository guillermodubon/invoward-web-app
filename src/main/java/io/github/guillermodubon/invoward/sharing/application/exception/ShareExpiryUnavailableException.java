package io.github.guillermodubon.invoward.sharing.application.exception;

/** Signals that the requested link duration has no remaining valid lifetime. */
public final class ShareExpiryUnavailableException extends RuntimeException {

    public ShareExpiryUnavailableException() {
        super("Share-link expiration is unavailable.");
    }
}
