package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that confirmed documents, extractions, or matches are not a complete reconciliation input. */
public final class ReconciliationInputNotReadyException extends RuntimeException {

    public ReconciliationInputNotReadyException() {
        super("The Analysis is not ready for reconciliation.");
    }
}
