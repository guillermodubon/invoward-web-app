package io.github.guillermodubon.invoward.extraction.application.exception;

/** Signals that classification is not valid for the Analysis workflow's current state. */
public final class DocumentClassificationConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentClassificationConflictException() {
        super("Document classification is not available in the current Analysis state");
    }
}
