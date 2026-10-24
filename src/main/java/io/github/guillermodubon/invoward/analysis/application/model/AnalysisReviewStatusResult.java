package io.github.guillermodubon.invoward.analysis.application.model;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Minimal safe result of changing an Analysis review status. */
public record AnalysisReviewStatusResult(
        UUID analysisId,
        AnalysisReviewStatus reviewStatus,
        Instant reviewedAt) {

    public AnalysisReviewStatusResult {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(reviewStatus, "reviewStatus must not be null");
        if (reviewStatus == AnalysisReviewStatus.PENDING && reviewedAt != null) {
            throw new IllegalArgumentException("pending review status must not have a reviewedAt timestamp");
        }
    }

    public static AnalysisReviewStatusResult from(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        return new AnalysisReviewStatusResult(analysis.id(), analysis.reviewStatus(), analysis.reviewedAt());
    }
}
