package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals persisted reconciliation state that is partial or inconsistent with its input. */
public final class ReconciliationConflictException extends RuntimeException {

    public ReconciliationConflictException() {
        super("Reconciliation conflicts with the persisted state.");
    }
}
