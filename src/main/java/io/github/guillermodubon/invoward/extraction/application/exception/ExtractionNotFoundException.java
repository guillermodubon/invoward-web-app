package io.github.guillermodubon.invoward.extraction.application.exception;

/** Signals that an owner-scoped Analysis has no readable extraction review. */
public final class ExtractionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExtractionNotFoundException() {
        super("Extraction was not found");
    }
}
