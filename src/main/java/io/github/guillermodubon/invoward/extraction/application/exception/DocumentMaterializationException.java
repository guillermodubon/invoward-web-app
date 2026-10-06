package io.github.guillermodubon.invoward.extraction.application.exception;

/** Safe local temporary-file failure; never includes a path or document contents. */
public final class DocumentMaterializationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentMaterializationException() {
        super("Private document materialization failed");
    }
}
