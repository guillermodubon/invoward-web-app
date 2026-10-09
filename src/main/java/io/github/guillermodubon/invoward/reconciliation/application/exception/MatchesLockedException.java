package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that manual matching is closed after the Analysis proceeds to reconciliation. */
public final class MatchesLockedException extends RuntimeException {

    public MatchesLockedException() {
        super("The match set is locked in its current workflow state.");
    }
}
