package io.github.guillermodubon.invoward.analysis.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted processing state for one Analysis. */
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

    /** Keeps the job idle until document upload is complete, without starting a worker attempt. */
    public AnalysisJob awaitMoreUploads(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER
                || (currentStage != AnalysisStatus.CREATED && currentStage != AnalysisStatus.UPLOADING)) {
            throw new IllegalStateException("Analysis job cannot wait for uploads in its current state");
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        return new AnalysisJob(
                id,
                analysisId,
                AnalysisJobStatus.WAITING_FOR_USER,
                AnalysisStatus.UPLOADING,
                attemptCount,
                false,
                lastErrorCode,
                lastErrorMessage,
                startedAt,
                completedAt,
                createdAt,
                now);
    }

    /** Keeps the job idle after classification, waiting for the user's type confirmation. */
    public AnalysisJob waitForUserAtClassification(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER || currentStage != AnalysisStatus.UPLOADING) {
            throw new IllegalStateException("Analysis job cannot wait for classification in its current state");
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.WAITING_FOR_USER, AnalysisStatus.CLASSIFYING,
                attemptCount, false, lastErrorCode, lastErrorMessage, startedAt, completedAt,
                createdAt, now);
    }

    /** Keeps synchronous extraction idle while its DRAFT results await human review. */
    public AnalysisJob waitForExtractionConfirmation(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER || currentStage != AnalysisStatus.CLASSIFYING) {
            throw new IllegalStateException("Analysis job cannot wait for extraction confirmation in its current state");
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.WAITING_FOR_USER, AnalysisStatus.AWAITING_CONFIRMATION,
                attemptCount, false, null, null, null, null, createdAt, now);
    }

    /** Keeps matching unstarted while the Analysis waits for its next persisted stage. */
    public AnalysisJob waitForMatching(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER
                || currentStage != AnalysisStatus.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("Analysis job cannot wait for matching in its current state");
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.WAITING_FOR_USER, AnalysisStatus.MATCHING,
                attemptCount, false, null, null, null, null, createdAt, now);
    }

    @Override
    public String toString() {
        return "AnalysisJob[id=" + id + ", analysisId=" + analysisId + ", status=" + status
                + ", currentStage=" + currentStage + "]";
    }
}
