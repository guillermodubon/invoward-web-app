package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that an owner-visible Analysis has no persisted reconciliation result. */
public final class ReconciliationNotFoundException extends RuntimeException {

    public ReconciliationNotFoundException() {
        super("Reconciliation was not found.");
    }
}
