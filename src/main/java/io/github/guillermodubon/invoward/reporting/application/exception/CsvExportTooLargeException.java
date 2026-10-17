package io.github.guillermodubon.invoward.reporting.application.exception;

/** Raised when a complete CSV export would exceed its configured byte limit. */
public final class CsvExportTooLargeException extends RuntimeException {

    public CsvExportTooLargeException() {
        super("CSV export exceeds the configured maximum size");
    }
}
