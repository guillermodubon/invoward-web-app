package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that reconciliation was requested while the Analysis is in another workflow state. */
public final class AnalysisReconciliationNotAllowedException extends RuntimeException {

    public AnalysisReconciliationNotAllowedException() {
        super("Analysis reconciliation is not allowed in its current state.");
    }
}
