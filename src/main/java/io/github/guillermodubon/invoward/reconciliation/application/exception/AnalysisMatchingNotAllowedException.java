package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that an Analysis is not in a state where its initial match set can be persisted. */
public final class AnalysisMatchingNotAllowedException extends RuntimeException {

    public AnalysisMatchingNotAllowedException() {
        super("Analysis matching is not allowed in its current state.");
    }
}
