package io.github.guillermodubon.invoward.extraction.application.exception;

/** Signals that the required reference and invoice inputs are not both available. */
public final class DocumentsRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentsRequiredException() {
        super("A reference document and an invoice are required");
    }
}
