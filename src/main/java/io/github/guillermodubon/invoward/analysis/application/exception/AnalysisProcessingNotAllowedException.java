package io.github.guillermodubon.invoward.analysis.application.exception;

/** Signals that a requested processing stage is not valid for the current Analysis state. */
public final class AnalysisProcessingNotAllowedException extends RuntimeException {

    public AnalysisProcessingNotAllowedException() {
        super("Analysis processing is not allowed in its current state.");
    }
}
