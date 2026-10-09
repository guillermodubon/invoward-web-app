package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that the requested match row is not part of the authorized Analysis. */
public final class MatchNotFoundException extends RuntimeException {

    public MatchNotFoundException() {
        super("The requested match was not found.");
    }
}
