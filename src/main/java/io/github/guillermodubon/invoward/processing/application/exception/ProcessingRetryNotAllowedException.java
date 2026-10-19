package io.github.guillermodubon.invoward.processing.application.exception;

/** Raised when the persisted Analysis/Job pair is not eligible for manual retry. */
public final class ProcessingRetryNotAllowedException extends RuntimeException {

    public ProcessingRetryNotAllowedException() {
        super("Analysis retry is not allowed in its current state");
    }
}
