package io.github.guillermodubon.invoward.reporting.application.exception;

/** Safe signal that an authorized Analysis has no available persisted report. */
public final class ReportNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReportNotFoundException() {
        super("Report was not found.");
    }
}
