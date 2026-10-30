package io.github.guillermodubon.invoward.sharing.application.exception;

/** Internal signal used to retry a share token whose unique hash already exists. */
public final class DuplicateShareTokenHashException extends RuntimeException {

    public DuplicateShareTokenHashException() {
        super("Share token hash already exists.");
    }
}
