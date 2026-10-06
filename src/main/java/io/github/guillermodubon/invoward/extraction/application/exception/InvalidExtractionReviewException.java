package io.github.guillermodubon.invoward.extraction.application.exception;

/** Safe validation failure for an invalid human extraction review. */
public final class InvalidExtractionReviewException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidExtractionReviewException() {
        super("The extraction review contains invalid data");
    }
}
