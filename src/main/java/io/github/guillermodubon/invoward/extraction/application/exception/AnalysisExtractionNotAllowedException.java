package io.github.guillermodubon.invoward.extraction.application.exception;

/** The Analysis is not in the state required to start document extraction. */
public final class AnalysisExtractionNotAllowedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AnalysisExtractionNotAllowedException() {
        super("Extraction is not allowed in the current Analysis state");
    }
}
