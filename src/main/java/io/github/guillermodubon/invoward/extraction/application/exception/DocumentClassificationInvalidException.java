package io.github.guillermodubon.invoward.extraction.application.exception;

/** Signals an absent or invalid provider classification without retaining provider output. */
public final class DocumentClassificationInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentClassificationInvalidException() {
        super("Document classification response is invalid");
    }
}
