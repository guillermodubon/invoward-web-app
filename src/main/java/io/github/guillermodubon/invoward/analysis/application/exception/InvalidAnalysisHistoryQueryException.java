package io.github.guillermodubon.invoward.analysis.application.exception;

/** Signals invalid or unsupported filters for the registered-user Analysis history query. */
public final class InvalidAnalysisHistoryQueryException extends IllegalArgumentException {

    public InvalidAnalysisHistoryQueryException() {
        super("History query is invalid.");
    }
}
