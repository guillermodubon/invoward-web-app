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
