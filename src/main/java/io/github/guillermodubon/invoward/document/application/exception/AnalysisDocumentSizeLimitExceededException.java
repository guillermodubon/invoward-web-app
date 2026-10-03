package io.github.guillermodubon.invoward.document.application.exception;

/** Safe rejection raised when stored and incoming documents exceed the Analysis size limit. */
public final class AnalysisDocumentSizeLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AnalysisDocumentSizeLimitExceededException() {
        super("Analysis documents exceed the configured combined size limit.");
    }
}
