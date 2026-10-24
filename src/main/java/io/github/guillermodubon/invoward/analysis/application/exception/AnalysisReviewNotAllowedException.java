package io.github.guillermodubon.invoward.analysis.application.exception;

/** Signals that an Analysis is not eligible for a review-status change. */
public final class AnalysisReviewNotAllowedException extends RuntimeException {

    public AnalysisReviewNotAllowedException() {
        super("Review status can be changed only for completed analyses.");
    }
}
