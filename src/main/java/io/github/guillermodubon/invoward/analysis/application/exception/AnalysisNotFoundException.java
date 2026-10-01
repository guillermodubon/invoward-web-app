package io.github.guillermodubon.invoward.analysis.application.exception;

/** Safe signal for an absent Analysis or one not owned by the requested identity. */
public final class AnalysisNotFoundException extends RuntimeException {

    public AnalysisNotFoundException() {
        super("Analysis was not found.");
    }
}
