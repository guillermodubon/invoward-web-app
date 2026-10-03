package io.github.guillermodubon.invoward.document.application.exception;

/** Safe conflict raised when an Analysis has advanced beyond its upload window. */
public final class AnalysisDocumentsLockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AnalysisDocumentsLockedException() {
        super("Analysis no longer accepts documents.");
    }
}
