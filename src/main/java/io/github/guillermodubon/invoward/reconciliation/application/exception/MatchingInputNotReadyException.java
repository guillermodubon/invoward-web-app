package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that the current documents or confirmed extractions cannot be used for matching. */
public final class MatchingInputNotReadyException extends RuntimeException {

    public MatchingInputNotReadyException() {
        super("Confirmed matching input is not ready.");
    }
}
