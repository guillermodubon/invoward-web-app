package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that a requested line is not an available unmatched counterpart. */
public final class MatchTargetUnavailableException extends RuntimeException {

    public MatchTargetUnavailableException() {
        super("The requested line is not available for matching.");
    }
}
