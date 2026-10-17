package io.github.guillermodubon.invoward.reporting.application.exception;

/** Safe signal that a complete confirmed reconciliation is not yet available for reporting. */
public final class ReportInputNotReadyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReportInputNotReadyException() {
        super("The persisted reconciliation is not ready for report generation");
    }

    public ReportInputNotReadyException(Throwable cause) {
        super("The persisted reconciliation is not ready for report generation", cause);
    }
}
