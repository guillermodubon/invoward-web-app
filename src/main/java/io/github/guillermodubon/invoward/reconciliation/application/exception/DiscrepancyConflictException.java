package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals a stale version or a resolution transition that conflicts with the current state. */
public final class DiscrepancyConflictException extends RuntimeException {

    public DiscrepancyConflictException() {
        super("The discrepancy changed while it was being reviewed. Reload and try again.");
    }
}
