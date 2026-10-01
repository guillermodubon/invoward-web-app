package io.github.guillermodubon.invoward.analysis.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe application read model for polling Analysis processing status. */
public record AnalysisStatusSnapshot(
        UUID analysisId,
        AnalysisStatus status,
        AnalysisJobStatus jobStatus,
        AnalysisStatus currentStage,
        boolean retryable,
        String failureCode,
        String failureMessage,
        Instant updatedAt) {

    public AnalysisStatusSnapshot {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(jobStatus, "jobStatus must not be null");
        Objects.requireNonNull(currentStage, "currentStage must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
