package io.github.guillermodubon.invoward.reporting.application.exception;

/** Safe signal that the Analysis is not in the report-generation stage. */
public final class AnalysisReportNotAllowedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AnalysisReportNotAllowedException() {
        super("Analysis is not ready for report generation");
    }
}
