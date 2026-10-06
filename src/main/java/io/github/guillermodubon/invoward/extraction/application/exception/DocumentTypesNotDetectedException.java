package io.github.guillermodubon.invoward.extraction.application.exception;

/** Extraction cannot start until both uploaded documents have a detected type. */
public final class DocumentTypesNotDetectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentTypesNotDetectedException() {
        super("Both document types must be detected before extraction");
    }
}
