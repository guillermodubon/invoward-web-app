package io.github.guillermodubon.invoward.analysis.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisJobTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void createsWaitingForUserJobWithExactInitialState() {
        UUID id = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();

        AnalysisJob job = AnalysisJob.waitingForUser(id, analysisId, NOW);

        assertEquals(id, job.id());
        assertEquals(analysisId, job.analysisId());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(AnalysisStatus.CREATED, job.currentStage());
        assertEquals(0, job.attemptCount());
        assertFalse(job.retryable());
        assertNull(job.lastErrorCode());
        assertNull(job.lastErrorMessage());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
        assertEquals(NOW, job.createdAt());
        assertEquals(NOW, job.updatedAt());
    }

    @Test
    void remainsWaitingForUserAtUploadingStageWithoutStartingAnAttempt() {
        UUID jobId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        AnalysisJob initial = AnalysisJob.waitingForUser(jobId, analysisId, NOW);
        Instant uploadAt = NOW.plusSeconds(10);

        AnalysisJob uploading = initial.awaitMoreUploads(uploadAt);

        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, uploading.status());
        assertEquals(AnalysisStatus.UPLOADING, uploading.currentStage());
        assertEquals(0, uploading.attemptCount());
        assertFalse(uploading.retryable());
        assertNull(uploading.lastErrorCode());
        assertNull(uploading.lastErrorMessage());
        assertNull(uploading.startedAt());
        assertNull(uploading.completedAt());
        assertEquals(NOW, uploading.createdAt());
        assertEquals(uploadAt, uploading.updatedAt());

        AnalysisJob uploadingAgain = uploading.awaitMoreUploads(uploadAt.plusSeconds(10));
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, uploadingAgain.status());
        assertEquals(AnalysisStatus.UPLOADING, uploadingAgain.currentStage());
        assertEquals(0, uploadingAgain.attemptCount());
        assertNull(uploadingAgain.startedAt());
    }

    @Test
    void rejectsUploadWaitTransitionWhenJobIsNotWaitingForUser() {
        AnalysisJob queued = new AnalysisJob(
                UUID.randomUUID(), UUID.randomUUID(), AnalysisJobStatus.QUEUED,
                AnalysisStatus.CREATED, 0, false, null, null, null, null, NOW, NOW);

        assertThrows(IllegalStateException.class, () -> queued.awaitMoreUploads(NOW.plusSeconds(1)));
    }

    @Test
    void waitsForUserAtClassificationWithoutStartingOrChangingAttempts() {
        AnalysisJob uploading = AnalysisJob.waitingForUser(UUID.randomUUID(), UUID.randomUUID(), NOW)
                .awaitMoreUploads(NOW.plusSeconds(1));

        AnalysisJob classifying = uploading.waitForUserAtClassification(NOW.plusSeconds(2));

        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, classifying.status());
        assertEquals(AnalysisStatus.CLASSIFYING, classifying.currentStage());
        assertEquals(uploading.attemptCount(), classifying.attemptCount());
        assertEquals(uploading.retryable(), classifying.retryable());
        assertEquals(uploading.startedAt(), classifying.startedAt());
        assertEquals(uploading.completedAt(), classifying.completedAt());
        assertEquals(uploading.createdAt(), classifying.createdAt());
        assertEquals(NOW.plusSeconds(2), classifying.updatedAt());
        assertThrows(IllegalStateException.class,
                () -> classifying.waitForUserAtClassification(NOW.plusSeconds(3)));
    }

    @Test
    void waitsForUserAtExtractionReviewWithoutStartingAnAttempt() {
        AnalysisJob classifying = AnalysisJob.waitingForUser(UUID.randomUUID(), UUID.randomUUID(), NOW)
                .awaitMoreUploads(NOW.plusSeconds(1))
                .waitForUserAtClassification(NOW.plusSeconds(2));

        AnalysisJob awaitingConfirmation = classifying.waitForExtractionConfirmation(NOW.plusSeconds(3));

        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, awaitingConfirmation.status());
        assertEquals(AnalysisStatus.AWAITING_CONFIRMATION, awaitingConfirmation.currentStage());
        assertEquals(classifying.attemptCount(), awaitingConfirmation.attemptCount());
        assertFalse(awaitingConfirmation.retryable());
        assertNull(awaitingConfirmation.lastErrorCode());
        assertNull(awaitingConfirmation.lastErrorMessage());
        assertNull(awaitingConfirmation.startedAt());
        assertNull(awaitingConfirmation.completedAt());
        assertEquals(classifying.createdAt(), awaitingConfirmation.createdAt());
        assertEquals(NOW.plusSeconds(3), awaitingConfirmation.updatedAt());
        assertThrows(IllegalStateException.class,
                () -> awaitingConfirmation.waitForExtractionConfirmation(NOW.plusSeconds(4)));
        assertThrows(IllegalArgumentException.class,
                () -> classifying.waitForExtractionConfirmation(NOW.plusSeconds(1)));
    }

    @Test
    void waitsForUserAtMatchingWithoutStartingAnAttempt() {
        AnalysisJob awaitingConfirmation = AnalysisJob.waitingForUser(
                UUID.randomUUID(), UUID.randomUUID(), NOW)
                .awaitMoreUploads(NOW.plusSeconds(1))
                .waitForUserAtClassification(NOW.plusSeconds(2))
                .waitForExtractionConfirmation(NOW.plusSeconds(3));

        AnalysisJob matching = awaitingConfirmation.waitForMatching(NOW.plusSeconds(4));

        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, matching.status());
        assertEquals(AnalysisStatus.MATCHING, matching.currentStage());
        assertEquals(awaitingConfirmation.attemptCount(), matching.attemptCount());
        assertFalse(matching.retryable());
        assertNull(matching.lastErrorCode());
        assertNull(matching.lastErrorMessage());
        assertNull(matching.startedAt());
        assertNull(matching.completedAt());
        assertEquals(awaitingConfirmation.createdAt(), matching.createdAt());
        assertEquals(NOW.plusSeconds(4), matching.updatedAt());
        assertThrows(IllegalStateException.class, () -> matching.waitForMatching(NOW.plusSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> awaitingConfirmation.waitForMatching(NOW.plusSeconds(2)));
    }

    @Test
    void requiresIdentifiersAndValidStateAndTimestamps() {
        UUID jobId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> AnalysisJob.waitingForUser(jobId, analysisId, null));
        assertThrows(NullPointerException.class,
                () -> new AnalysisJob(null, analysisId, AnalysisJobStatus.WAITING_FOR_USER,
                        AnalysisStatus.CREATED, 0, false, null, null, null, null, NOW, NOW));
        assertThrows(NullPointerException.class,
                () -> new AnalysisJob(jobId, null, AnalysisJobStatus.WAITING_FOR_USER,
                        AnalysisStatus.CREATED, 0, false, null, null, null, null, NOW, NOW));
        assertThrows(NullPointerException.class,
                () -> new AnalysisJob(jobId, analysisId, null,
                        AnalysisStatus.CREATED, 0, false, null, null, null, null, NOW, NOW));
        assertThrows(NullPointerException.class,
                () -> new AnalysisJob(jobId, analysisId, AnalysisJobStatus.WAITING_FOR_USER,
                        null, 0, false, null, null, null, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisJob(jobId, analysisId, AnalysisJobStatus.WAITING_FOR_USER,
                        AnalysisStatus.CREATED, -1, false, null, null, null, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisJob(jobId, analysisId, AnalysisJobStatus.WAITING_FOR_USER,
                        AnalysisStatus.CREATED, 0, false, null, null, null, null, NOW, NOW.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisJob(jobId, analysisId, AnalysisJobStatus.WAITING_FOR_USER,
                        AnalysisStatus.CREATED, 0, false, null, null, NOW.plusSeconds(1), null, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisJob(jobId, analysisId, AnalysisJobStatus.COMPLETED,
                        AnalysisStatus.COMPLETED, 0, false, null, null, null, NOW, NOW, NOW));
    }
}
