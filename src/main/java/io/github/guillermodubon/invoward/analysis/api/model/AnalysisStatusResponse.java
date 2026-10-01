package io.github.guillermodubon.invoward.analysis.api.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe public projection of Analysis processing status. */
public record AnalysisStatusResponse(
        UUID analysisId,
        AnalysisStatus status,
        AnalysisJobStatus jobStatus,
        AnalysisStatus currentStage,
        boolean retryable,
        String failureCode,
        String failureMessage,
        Instant updatedAt) {

    public static AnalysisStatusResponse from(AnalysisStatusSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return new AnalysisStatusResponse(
                snapshot.analysisId(),
                snapshot.status(),
                snapshot.jobStatus(),
                snapshot.currentStage(),
                snapshot.retryable(),
                snapshot.failureCode(),
                snapshot.failureMessage(),
                snapshot.updatedAt());
    }
}
