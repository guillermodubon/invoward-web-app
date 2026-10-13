package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that persisted matches changed after the reconciliation input was assembled. */
public final class ReconciliationInputChangedException extends RuntimeException {

    public ReconciliationInputChangedException() {
        super("Matching input changed during reconciliation.");
    }
}
