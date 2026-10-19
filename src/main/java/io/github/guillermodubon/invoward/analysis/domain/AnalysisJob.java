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
        if (completedAt != null && ((startedAt != null && completedAt.isBefore(startedAt))
                || completedAt.isBefore(createdAt)
                || completedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("completedAt must not precede its start or exceed updatedAt");
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
                0,
                false,
                null,
                null,
                null,
                null,
                createdAt,
                now);
    }

    /** Queues the first machine stage once both required documents are uploaded. */
    public AnalysisJob queueClassification(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER || currentStage != AnalysisStatus.UPLOADING) {
            throw new IllegalStateException("Analysis job can queue classification only after uploads");
        }
        return queueStage(AnalysisStatus.CLASSIFYING, now);
    }

    /** Finishes classification and leaves detected types at the human confirmation gate. */
    public AnalysisJob finishClassificationAwaitingUser(Instant now) {
        requireRunningStage(AnalysisStatus.CLASSIFYING, now);
        return waitingAt(AnalysisStatus.CLASSIFYING, now);
    }

    /** Queues extraction after the user has confirmed the detected document types. */
    public AnalysisJob queueExtraction(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER || currentStage != AnalysisStatus.CLASSIFYING) {
            throw new IllegalStateException("Analysis job can queue extraction only after classification review");
        }
        return queueStage(AnalysisStatus.EXTRACTING, now);
    }

    /** Finishes extraction and waits for human review of the extracted data. */
    public AnalysisJob finishExtractionAwaitingConfirmation(Instant now) {
        requireRunningStage(AnalysisStatus.EXTRACTING, now);
        return waitingAt(AnalysisStatus.AWAITING_CONFIRMATION, now);
    }

    /** Queues matching after extraction has been confirmed. */
    public AnalysisJob queueMatching(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER
                || currentStage != AnalysisStatus.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("Analysis job can queue matching only after extraction confirmation");
        }
        return queueStage(AnalysisStatus.MATCHING, now);
    }

    /** Finishes matching and leaves ambiguous pairs at the human review gate. */
    public AnalysisJob finishMatchingAwaitingReview(Instant now) {
        requireRunningStage(AnalysisStatus.MATCHING, now);
        return waitingAt(AnalysisStatus.AWAITING_MATCH_REVIEW, now);
    }

    /** Queues reconciliation after automatic matching or user-confirmed match review. */
    public AnalysisJob queueReconciliation(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        boolean afterAutomaticMatching = status == AnalysisJobStatus.RUNNING
                && currentStage == AnalysisStatus.MATCHING;
        boolean afterMatchGate = status == AnalysisJobStatus.WAITING_FOR_USER
                && (currentStage == AnalysisStatus.MATCHING
                || currentStage == AnalysisStatus.AWAITING_MATCH_REVIEW);
        if (!afterAutomaticMatching && !afterMatchGate) {
            throw new IllegalStateException("Analysis job can queue reconciliation only after matching");
        }
        return queueStage(AnalysisStatus.RECONCILING, now);
    }

    /** Queues report generation after reconciliation has been persisted. */
    public AnalysisJob queueReportGeneration(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        boolean completedReconciliation = status == AnalysisJobStatus.RUNNING
                && currentStage == AnalysisStatus.RECONCILING;
        boolean legacyReconciliationGate = status == AnalysisJobStatus.WAITING_FOR_USER
                && currentStage == AnalysisStatus.RECONCILING;
        if (!completedReconciliation && !legacyReconciliationGate) {
            throw new IllegalStateException("Analysis job can queue report generation only after reconciliation");
        }
        return queueStage(AnalysisStatus.GENERATING_REPORT, now);
    }

    /** Claims a queued machine stage and records the next bounded attempt. */
    public AnalysisJob startQueuedAttempt(Instant now, int maxAttemptsPerStage) {
        Objects.requireNonNull(now, "now must not be null");
        requirePositiveMaxAttempts(maxAttemptsPerStage);
        if (status != AnalysisJobStatus.QUEUED || !isMachineStage(currentStage)) {
            throw new IllegalStateException("Only a queued machine stage can start an attempt");
        }
        if (attemptCount >= maxAttemptsPerStage) {
            throw new IllegalStateException("Analysis job reached the configured attempt limit");
        }
        requireTransitionTime(now);
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.RUNNING, currentStage,
                attemptCount + 1, false, null, null, now, null, createdAt, now);
    }

    /** Fails the active attempt while keeping only classified, bounded error data. */
    public AnalysisJob failCurrentAttempt(
            String safeCode,
            String safeInternalMessage,
            boolean retryableCandidate,
            int maxAttemptsPerStage,
            Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requirePositiveMaxAttempts(maxAttemptsPerStage);
        requireRunningStage(currentStage, now);
        requireSafeCode(safeCode, "safeCode");
        requireSafeMessage(safeInternalMessage, "safeInternalMessage");
        boolean effectiveRetryable = retryableCandidate && attemptCount < maxAttemptsPerStage;
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.FAILED, currentStage,
                attemptCount, effectiveRetryable, safeCode, safeInternalMessage,
                startedAt, null, createdAt, now);
    }

    /** Terminalizes a queued job that became ineligible before an attempt could start. */
    public AnalysisJob failQueuedProcessing(
            String safeCode,
            String safeInternalMessage,
            Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.QUEUED || !isMachineStage(currentStage)) {
            throw new IllegalStateException("Only a queued machine stage can fail before an attempt starts");
        }
        requireSafeCode(safeCode, "safeCode");
        requireSafeMessage(safeInternalMessage, "safeInternalMessage");
        requireTransitionTime(now);
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.FAILED, currentStage,
                attemptCount, false, safeCode, safeInternalMessage,
                null, null, createdAt, now);
    }

    /** Requeues the failed stage without consuming an attempt; the next claim increments it. */
    public AnalysisJob retryFailedStage(Instant now, int maxAttemptsPerStage) {
        Objects.requireNonNull(now, "now must not be null");
        requirePositiveMaxAttempts(maxAttemptsPerStage);
        if (status != AnalysisJobStatus.FAILED || !isMachineStage(currentStage)
                || !retryable || attemptCount >= maxAttemptsPerStage) {
            throw new IllegalStateException("Analysis job is not eligible for retry");
        }
        requireTransitionTime(now);
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.QUEUED, currentStage,
                attemptCount, false, null, null, null, null, createdAt, now);
    }

    /** Requeues an interrupted attempt, or safely terminalizes it at the configured attempt limit. */
    public AnalysisJob recoverInterruptedAttempt(int maxAttemptsPerStage, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requirePositiveMaxAttempts(maxAttemptsPerStage);
        requireRunningStage(currentStage, now);
        if (attemptCount >= maxAttemptsPerStage) {
            return failCurrentAttempt(
                    "PROCESSING_RECOVERY_LIMIT_EXCEEDED",
                    "Interrupted processing reached the configured attempt limit.",
                    false,
                    maxAttemptsPerStage,
                    now);
        }
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.QUEUED, currentStage,
                attemptCount, false, null, null, null, null, createdAt, now);
    }

    /** Requeues a legacy machine stage that was left waiting before asynchronous processing existed. */
    public AnalysisJob queueLegacyMachineStage(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER || !isMachineStage(currentStage)) {
            throw new IllegalStateException("Only a legacy waiting machine stage can be queued");
        }
        return queueStage(currentStage, now);
    }

    /** Fails a recoverable job safely when startup finds an expired owner or inconsistent persisted state. */
    public AnalysisJob failRecovery(
            AnalysisStatus failedStage,
            String safeCode,
            String safeInternalMessage,
            Instant now) {
        Objects.requireNonNull(failedStage, "failedStage must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if ((status != AnalysisJobStatus.RUNNING && status != AnalysisJobStatus.WAITING_FOR_USER)
                || !isMachineStage(failedStage)) {
            throw new IllegalStateException("Only a recoverable machine job can fail during recovery");
        }
        requireSafeCode(safeCode, "safeCode");
        requireSafeMessage(safeInternalMessage, "safeInternalMessage");
        requireTransitionTime(now);
        Instant failedStart = status == AnalysisJobStatus.RUNNING ? startedAt : null;
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.FAILED, failedStage,
                attemptCount, false, safeCode, safeInternalMessage,
                failedStart, null, createdAt, now);
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
                0, false, null, null, null, null,
                createdAt, now);
    }

    /** Keeps synchronous extraction idle while its DRAFT results await human review. */
    public AnalysisJob waitForExtractionConfirmation(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER
                || (currentStage != AnalysisStatus.CLASSIFYING
                && currentStage != AnalysisStatus.EXTRACTING)) {
            throw new IllegalStateException("Analysis job cannot wait for extraction confirmation in its current state");
        }
        requireTransitionTime(now);
        return waitingAt(AnalysisStatus.AWAITING_CONFIRMATION, now);
    }

    /** Keeps matching unstarted while the Analysis waits for its next persisted stage. */
    public AnalysisJob waitForMatching(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER
                || currentStage != AnalysisStatus.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("Analysis job cannot wait for matching in its current state");
        }
        requireTransitionTime(now);
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.WAITING_FOR_USER, AnalysisStatus.MATCHING,
                0, false, null, null, null, null, createdAt, now);
    }

    /** Keeps the job idle while match rows await user review. */
    public AnalysisJob waitForMatchReview(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER || currentStage != AnalysisStatus.MATCHING) {
            throw new IllegalStateException("Analysis job cannot wait for match review in its current state");
        }
        requireTransitionTime(now);
        return withStage(AnalysisStatus.AWAITING_MATCH_REVIEW, now);
    }

    /** Keeps the job idle after automatic matching or user-confirmed match review. */
    public AnalysisJob waitForReconciliation(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER
                || (currentStage != AnalysisStatus.MATCHING
                && currentStage != AnalysisStatus.AWAITING_MATCH_REVIEW)) {
            throw new IllegalStateException("Analysis job cannot wait for reconciliation in its current state");
        }
        requireTransitionTime(now);
        return withStage(AnalysisStatus.RECONCILING, now);
    }

    /** Leaves reconciliation idle and ready for the report-generation stage without starting a worker attempt. */
    public AnalysisJob awaitReportGeneration(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.WAITING_FOR_USER || currentStage != AnalysisStatus.RECONCILING) {
            throw new IllegalStateException("Analysis job cannot await report generation in its current state");
        }
        requireTransitionTime(now);
        return withStage(AnalysisStatus.GENERATING_REPORT, now);
    }

    /** Completes report generation after a claimed worker attempt. */
    public AnalysisJob completeReportGeneration(Instant generatedAt) {
        requireRunningStage(AnalysisStatus.GENERATING_REPORT, generatedAt);
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.COMPLETED, AnalysisStatus.COMPLETED,
                attemptCount, false, null, null, startedAt, generatedAt, createdAt, generatedAt);
    }

    private void requireTransitionTime(Instant now) {
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
    }

    private AnalysisJob queueStage(AnalysisStatus nextStage, Instant now) {
        if (!isMachineStage(nextStage)) {
            throw new IllegalStateException("Analysis job can queue only a machine stage");
        }
        requireTransitionTime(now);
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.QUEUED, nextStage,
                0, false, null, null, null, null, createdAt, now);
    }

    private AnalysisJob waitingAt(AnalysisStatus nextStage, Instant now) {
        requireTransitionTime(now);
        return new AnalysisJob(
                id, analysisId, AnalysisJobStatus.WAITING_FOR_USER, nextStage,
                0, false, null, null, null, null, createdAt, now);
    }

    private void requireRunningStage(AnalysisStatus expectedStage, Instant now) {
        Objects.requireNonNull(expectedStage, "expectedStage must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisJobStatus.RUNNING || currentStage != expectedStage || !isMachineStage(currentStage)) {
            throw new IllegalStateException("Analysis job is not running the expected machine stage");
        }
        if (attemptCount < 1 || startedAt == null) {
            throw new IllegalStateException("A running Analysis job must have a started attempt");
        }
        requireTransitionTime(now);
    }

    private static boolean isMachineStage(AnalysisStatus stage) {
        return stage == AnalysisStatus.CLASSIFYING
                || stage == AnalysisStatus.EXTRACTING
                || stage == AnalysisStatus.MATCHING
                || stage == AnalysisStatus.RECONCILING
                || stage == AnalysisStatus.GENERATING_REPORT;
    }

    private static void requirePositiveMaxAttempts(int maxAttemptsPerStage) {
        if (maxAttemptsPerStage < 1) {
            throw new IllegalArgumentException("maxAttemptsPerStage must be positive");
        }
    }

    private static void requireSafeCode(String value, String fieldName) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw new IllegalArgumentException(fieldName + " must be a safe uppercase code");
        }
    }

    private static void requireSafeMessage(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > 500
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must be a safe non-empty message");
        }
    }

    private AnalysisJob withStage(AnalysisStatus nextStage, Instant now) {
        return waitingAt(nextStage, now);
    }

    @Override
    public String toString() {
        return "AnalysisJob[id=" + id + ", analysisId=" + analysisId + ", status=" + status
                + ", currentStage=" + currentStage + "]";
    }
}
