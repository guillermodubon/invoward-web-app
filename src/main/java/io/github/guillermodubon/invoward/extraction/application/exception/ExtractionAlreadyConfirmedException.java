package io.github.guillermodubon.invoward.extraction.application.exception;

/** Signals that extraction has already been confirmed and cannot be regenerated. */
public final class ExtractionAlreadyConfirmedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExtractionAlreadyConfirmedException() {
        super("Extraction has already been confirmed");
    }
}
