package io.github.guillermodubon.invoward.extraction.application.exception;

/** Signals that a confirmed extraction is immutable. */
public final class ExtractionLockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExtractionLockedException() {
        super("The confirmed extraction is locked");
    }
}
