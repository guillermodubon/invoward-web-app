package io.github.guillermodubon.invoward.extraction.application.exception;

/** A requested human-confirmed type is not permitted for the corresponding document role. */
public final class ConfirmedDocumentTypeInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfirmedDocumentTypeInvalidException() {
        super("The confirmed document type is invalid for its role");
    }
}
