package io.github.guillermodubon.invoward.reporting.application.exception;

/** Provider-neutral failure raised when a report renderer cannot produce its output. */
public final class ReportGenerationException extends RuntimeException {

    public ReportGenerationException(Throwable cause) {
        super("Report generation failed", cause);
    }
}
