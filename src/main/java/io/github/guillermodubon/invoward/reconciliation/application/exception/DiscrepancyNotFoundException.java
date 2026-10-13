package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that an owner-visible Analysis does not contain the requested discrepancy. */
public final class DiscrepancyNotFoundException extends RuntimeException {

    public DiscrepancyNotFoundException() {
        super("Discrepancy was not found.");
    }
}
