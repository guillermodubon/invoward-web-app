package io.github.guillermodubon.invoward.reporting.application.exception;

/** Safe signal that persisted report/workflow state is inconsistent or changed concurrently. */
public final class ReportConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReportConflictException() {
        super("Report conflicts with the current Analysis state");
    }
}
