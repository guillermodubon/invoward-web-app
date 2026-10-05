package io.github.guillermodubon.invoward.extraction.application.exception;

/** Signals an inconsistent or conflicting extraction workflow state. */
public final class ExtractionConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExtractionConflictException() {
        super("Extraction is not available in the current Analysis state");
    }
}
