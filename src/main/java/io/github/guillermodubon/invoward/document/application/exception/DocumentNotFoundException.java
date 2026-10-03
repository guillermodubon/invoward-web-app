package io.github.guillermodubon.invoward.document.application.exception;

/** Safe signal for an absent document or one outside the requested Analysis. */
public final class DocumentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentNotFoundException() {
        super("Document was not found.");
    }
}
