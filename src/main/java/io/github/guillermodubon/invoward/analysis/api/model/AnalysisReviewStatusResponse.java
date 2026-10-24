package io.github.guillermodubon.invoward.analysis.api.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisReviewStatusResult;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe HTTP projection returned after changing an Analysis review state. */
public record AnalysisReviewStatusResponse(
        UUID analysisId,
        AnalysisReviewStatus reviewStatus,
        Instant reviewedAt) {

    public static AnalysisReviewStatusResponse from(AnalysisReviewStatusResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new AnalysisReviewStatusResponse(result.analysisId(), result.reviewStatus(), result.reviewedAt());
    }
}
