package io.github.guillermodubon.invoward.analysis.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted processing state for an Analysis. Only the initial state is created in this block. */
public record AnalysisJob(
        UUID id,
        UUID analysisId,
        AnalysisJobStatus status,
        AnalysisStatus currentStage,
        int attemptCount,
        boolean retryable,
        String lastErrorCode,
        String lastErrorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public AnalysisJob {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(currentStage, "currentStage must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (startedAt != null && (startedAt.isBefore(createdAt) || startedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("startedAt must be between createdAt and updatedAt");
        }
        if (completedAt != null && (startedAt == null
                || completedAt.isBefore(startedAt)
                || completedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("completedAt must follow startedAt and not exceed updatedAt");
        }
    }

    public static AnalysisJob waitingForUser(UUID id, UUID analysisId, Instant now) {
        return new AnalysisJob(
                id,
                analysisId,
                AnalysisJobStatus.WAITING_FOR_USER,
                AnalysisStatus.CREATED,
                0,
                false,
                null,
                null,
                null,
                null,
                now,
                now);
    }

    @Override
    public String toString() {
        return "AnalysisJob[id=" + id + ", analysisId=" + analysisId + ", status=" + status
                + ", currentStage=" + currentStage + "]";
    }
}
