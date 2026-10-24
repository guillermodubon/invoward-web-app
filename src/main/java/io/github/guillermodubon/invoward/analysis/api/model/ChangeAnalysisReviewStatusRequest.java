package io.github.guillermodubon.invoward.analysis.api.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;

import java.util.Objects;

/** Request body for changing the private review state of a completed Analysis. */
public record ChangeAnalysisReviewStatusRequest(AnalysisReviewStatus reviewStatus) {

    public ChangeAnalysisReviewStatusRequest {
        Objects.requireNonNull(reviewStatus, "reviewStatus must not be null");
    }
}
