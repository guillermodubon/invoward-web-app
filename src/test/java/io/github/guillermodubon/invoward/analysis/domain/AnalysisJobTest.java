package io.github.guillermodubon.invoward.analysis.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void queuesAndFinishesClassificationWithBoundedAttemptState() {
        AnalysisJob uploading = new AnalysisJob(
                UUID.randomUUID(), UUID.randomUUID(), AnalysisJobStatus.WAITING_FOR_USER,
                AnalysisStatus.UPLOADING, 2, true, "OLD_FAILURE", "Old safe message",
                NOW.plusSeconds(1), null, NOW, NOW.plusSeconds(1));

        AnalysisJob queued = uploading.queueClassification(NOW.plusSeconds(2));
        assertQueuedStage(queued, AnalysisStatus.CLASSIFYING, 0, NOW.plusSeconds(2));

        AnalysisJob running = queued.startQueuedAttempt(NOW.plusSeconds(3), 3);
        assertRunningStage(running, AnalysisStatus.CLASSIFYING, 1, NOW.plusSeconds(3));

        AnalysisJob awaitingTypes = running.finishClassificationAwaitingUser(NOW.plusSeconds(4));
        assertWaitingStage(awaitingTypes, AnalysisStatus.CLASSIFYING, NOW.plusSeconds(4));
        assertThrows(IllegalStateException.class,
                () -> queued.finishClassificationAwaitingUser(NOW.plusSeconds(4)));
    }

    @Test
    void queuesAndFinishesExtractionAndMatchingAtTheirUserGates() {
        AnalysisJob awaitingTypes = AnalysisJob.waitingForUser(UUID.randomUUID(), UUID.randomUUID(), NOW)
                .awaitMoreUploads(NOW.plusSeconds(1))
                .queueClassification(NOW.plusSeconds(2))
                .startQueuedAttempt(NOW.plusSeconds(3), 3)
                .finishClassificationAwaitingUser(NOW.plusSeconds(4));

        AnalysisJob queuedExtraction = awaitingTypes.queueExtraction(NOW.plusSeconds(5));
        assertQueuedStage(queuedExtraction, AnalysisStatus.EXTRACTING, 0, NOW.plusSeconds(5));
        AnalysisJob extraction = queuedExtraction.startQueuedAttempt(NOW.plusSeconds(6), 3);
        AnalysisJob awaitingConfirmation = extraction.finishExtractionAwaitingConfirmation(NOW.plusSeconds(7));
        assertWaitingStage(awaitingConfirmation, AnalysisStatus.AWAITING_CONFIRMATION, NOW.plusSeconds(7));

        AnalysisJob queuedMatching = awaitingConfirmation.queueMatching(NOW.plusSeconds(8));
        assertQueuedStage(queuedMatching, AnalysisStatus.MATCHING, 0, NOW.plusSeconds(8));
        AnalysisJob matching = queuedMatching.startQueuedAttempt(NOW.plusSeconds(9), 3);
        AnalysisJob awaitingReview = matching.finishMatchingAwaitingReview(NOW.plusSeconds(10));
        assertWaitingStage(awaitingReview, AnalysisStatus.AWAITING_MATCH_REVIEW, NOW.plusSeconds(10));

        assertThrows(IllegalStateException.class,
                () -> extraction.finishClassificationAwaitingUser(NOW.plusSeconds(7)));
        assertThrows(IllegalStateException.class,
                () -> awaitingTypes.queueMatching(NOW.plusSeconds(5)));
    }

    @Test
    void queuesNextStagesAndCompletesReportAfterWorkerAttempt() {
        AnalysisJob awaitingReview = AnalysisJob.waitingForUser(UUID.randomUUID(), UUID.randomUUID(), NOW)
                .awaitMoreUploads(NOW.plusSeconds(1))
                .queueClassification(NOW.plusSeconds(2))
                .startQueuedAttempt(NOW.plusSeconds(3), 3)
                .finishClassificationAwaitingUser(NOW.plusSeconds(4))
                .queueExtraction(NOW.plusSeconds(5))
                .startQueuedAttempt(NOW.plusSeconds(6), 3)
                .finishExtractionAwaitingConfirmation(NOW.plusSeconds(7))
                .queueMatching(NOW.plusSeconds(8))
                .startQueuedAttempt(NOW.plusSeconds(9), 3)
                .finishMatchingAwaitingReview(NOW.plusSeconds(10));

        AnalysisJob queuedReconciliation = awaitingReview.queueReconciliation(NOW.plusSeconds(11));
        assertQueuedStage(queuedReconciliation, AnalysisStatus.RECONCILING, 0, NOW.plusSeconds(11));
        AnalysisJob reconciliation = queuedReconciliation.startQueuedAttempt(NOW.plusSeconds(12), 3);

        AnalysisJob queuedReport = reconciliation.queueReportGeneration(NOW.plusSeconds(13));
        assertQueuedStage(queuedReport, AnalysisStatus.GENERATING_REPORT, 0, NOW.plusSeconds(13));
        AnalysisJob report = queuedReport.startQueuedAttempt(NOW.plusSeconds(14), 3);
        AnalysisJob completed = report.completeReportGeneration(NOW.plusSeconds(15));

        assertEquals(AnalysisJobStatus.COMPLETED, completed.status());
        assertEquals(AnalysisStatus.COMPLETED, completed.currentStage());
        assertEquals(1, completed.attemptCount());
        assertEquals(NOW.plusSeconds(14), completed.startedAt());
        assertEquals(NOW.plusSeconds(15), completed.completedAt());
        assertEquals(NOW.plusSeconds(15), completed.updatedAt());
        assertFalse(completed.retryable());
        assertNull(completed.lastErrorCode());
        assertNull(completed.lastErrorMessage());

        AnalysisJob automaticReconciliation = runningJob(AnalysisStatus.MATCHING, 1)
                .queueReconciliation(NOW.plusSeconds(3));
        assertQueuedStage(automaticReconciliation, AnalysisStatus.RECONCILING, 0, NOW.plusSeconds(3));
    }

    @Test
    void claimsQueuedAttemptsAndEnforcesLimitAndMonotonicTime() {
        AnalysisJob queued = queuedJob(AnalysisStatus.EXTRACTING, 1);

        AnalysisJob running = queued.startQueuedAttempt(NOW.plusSeconds(3), 3);

        assertRunningStage(running, AnalysisStatus.EXTRACTING, 2, NOW.plusSeconds(3));
        assertThrows(IllegalStateException.class,
                () -> queued.startQueuedAttempt(NOW.plusSeconds(3), 1));
        assertThrows(IllegalStateException.class,
                () -> running.startQueuedAttempt(NOW.plusSeconds(4), 3));
        assertThrows(IllegalArgumentException.class,
                () -> queued.startQueuedAttempt(NOW, 3));
        assertThrows(IllegalArgumentException.class,
                () -> queued.startQueuedAttempt(NOW.plusSeconds(3), 0));
        AnalysisJob nonMachine = new AnalysisJob(
                UUID.randomUUID(), UUID.randomUUID(), AnalysisJobStatus.QUEUED,
                AnalysisStatus.AWAITING_CONFIRMATION, 0, false, null, null, null, null, NOW, NOW);
        assertThrows(IllegalStateException.class,
                () -> nonMachine.startQueuedAttempt(NOW.plusSeconds(1), 3));
    }

    @Test
    void failureKeepsFailedStageAndCapsEffectiveRetryability() {
        AnalysisJob running = runningJob(AnalysisStatus.RECONCILING, 2);

        AnalysisJob retryableFailure = running.failCurrentAttempt(
                "DATABASE_TEMPORARILY_UNAVAILABLE", "database temporarily unavailable",
                true, 3, NOW.plusSeconds(3));
        assertFailedStage(retryableFailure, AnalysisStatus.RECONCILING, 2, true, NOW.plusSeconds(3));

        AnalysisJob cappedFailure = runningJob(AnalysisStatus.RECONCILING, 3).failCurrentAttempt(
                "DATABASE_TEMPORARILY_UNAVAILABLE", "database temporarily unavailable",
                true, 3, NOW.plusSeconds(3));
        assertFailedStage(cappedFailure, AnalysisStatus.RECONCILING, 3, false, NOW.plusSeconds(3));

        assertThrows(IllegalStateException.class, () -> queuedJob(AnalysisStatus.RECONCILING, 0)
                .failCurrentAttempt("PROCESSING_FAILED", "safe failure", true, 3, NOW.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> running.failCurrentAttempt(
                "provider response with details", "safe failure", true, 3, NOW.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> running.failCurrentAttempt(
                "PROCESSING_FAILED", "unsafe\nmessage", true, 3, NOW.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> running.failCurrentAttempt(
                "PROCESSING_FAILED", "safe failure", true, 0, NOW.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> running.failCurrentAttempt(
                "PROCESSING_FAILED", "safe failure", true, 3, NOW.plusSeconds(1)));
    }

    @Test
    void queuedExpiryFailureDoesNotPretendAnAttemptStarted() {
        AnalysisJob queued = queuedJob(AnalysisStatus.EXTRACTING, 1);

        AnalysisJob failed = queued.failQueuedProcessing(
                "ANALYSIS_EXPIRED", "Guest analysis expired before processing started.", NOW.plusSeconds(2));

        assertEquals(AnalysisJobStatus.FAILED, failed.status());
        assertEquals(AnalysisStatus.EXTRACTING, failed.currentStage());
        assertEquals(1, failed.attemptCount());
        assertFalse(failed.retryable());
        assertEquals("ANALYSIS_EXPIRED", failed.lastErrorCode());
        assertEquals("Guest analysis expired before processing started.", failed.lastErrorMessage());
        assertNull(failed.startedAt());
        assertNull(failed.completedAt());
        assertEquals(queued.createdAt(), failed.createdAt());
        assertEquals(NOW.plusSeconds(2), failed.updatedAt());

        assertThrows(IllegalStateException.class, () -> runningJob(AnalysisStatus.EXTRACTING, 1)
                .failQueuedProcessing("ANALYSIS_EXPIRED", "Safe message", NOW.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> queued.failQueuedProcessing(
                "provider failure", "Safe message", NOW.plusSeconds(2)));
    }

    @Test
    void retriesFailedStageWithoutConsumingAttemptAndNextClaimIncrementsIt() {
        AnalysisJob failed = runningJob(AnalysisStatus.MATCHING, 1).failCurrentAttempt(
                "AI_PROVIDER_UNAVAILABLE", "matching assistance unavailable", true, 3, NOW.plusSeconds(3));

        AnalysisJob queued = failed.retryFailedStage(NOW.plusSeconds(4), 3);
        assertQueuedStage(queued, AnalysisStatus.MATCHING, 1, NOW.plusSeconds(4));
        AnalysisJob nextAttempt = queued.startQueuedAttempt(NOW.plusSeconds(5), 3);
        assertRunningStage(nextAttempt, AnalysisStatus.MATCHING, 2, NOW.plusSeconds(5));

        assertThrows(IllegalStateException.class, () -> failed.retryFailedStage(NOW.plusSeconds(4), 1));
        assertThrows(IllegalStateException.class, () -> failed.retryFailedStage(NOW.plusSeconds(3), 3)
                .retryFailedStage(NOW.plusSeconds(4), 3));
        AnalysisJob notRetryable = runningJob(AnalysisStatus.MATCHING, 3).failCurrentAttempt(
                "PROCESSING_FAILED", "safe failure", true, 3, NOW.plusSeconds(3));
        assertThrows(IllegalStateException.class, () -> notRetryable.retryFailedStage(NOW.plusSeconds(4), 3));
    }

    @Test
    void interruptedAttemptIsRequeuedOrTerminalizedAtLimit() {
        AnalysisJob running = runningJob(AnalysisStatus.EXTRACTING, 1);

        AnalysisJob recovered = running.recoverInterruptedAttempt(3, NOW.plusSeconds(3));
        assertQueuedStage(recovered, AnalysisStatus.EXTRACTING, 1, NOW.plusSeconds(3));

        AnalysisJob limited = runningJob(AnalysisStatus.EXTRACTING, 3)
                .recoverInterruptedAttempt(3, NOW.plusSeconds(3));
        assertFailedStage(limited, AnalysisStatus.EXTRACTING, 3, false, NOW.plusSeconds(3));
        assertEquals("PROCESSING_RECOVERY_LIMIT_EXCEEDED", limited.lastErrorCode());
        assertThrows(IllegalStateException.class,
                () -> queuedJob(AnalysisStatus.EXTRACTING, 1).recoverInterruptedAttempt(3, NOW.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class,
                () -> running.recoverInterruptedAttempt(3, NOW.plusSeconds(1)));
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
    void waitsForUserAtMatchReviewWithoutStartingAnAttempt() {
        AnalysisJob matching = matchingJob();
        Instant reviewAt = NOW.plusSeconds(5);

        AnalysisJob awaitingReview = matching.waitForMatchReview(reviewAt);

        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, awaitingReview.status());
        assertEquals(AnalysisStatus.AWAITING_MATCH_REVIEW, awaitingReview.currentStage());
        assertEquals(matching.attemptCount(), awaitingReview.attemptCount());
        assertFalse(awaitingReview.retryable());
        assertEquals(matching.lastErrorCode(), awaitingReview.lastErrorCode());
        assertEquals(matching.lastErrorMessage(), awaitingReview.lastErrorMessage());
        assertEquals(matching.startedAt(), awaitingReview.startedAt());
        assertEquals(matching.completedAt(), awaitingReview.completedAt());
        assertEquals(matching.createdAt(), awaitingReview.createdAt());
        assertEquals(reviewAt, awaitingReview.updatedAt());
        assertThrows(IllegalStateException.class,
                () -> awaitingReview.waitForMatchReview(reviewAt.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> matching.waitForMatchReview(matching.updatedAt().minusSeconds(1)));
    }

    @Test
    void waitsForUserAtReconciliationFromMatchingOrMatchReview() {
        AnalysisJob matching = matchingJob();
        Instant reconciliationAt = NOW.plusSeconds(5);

        AnalysisJob afterAutomaticMatches = matching.waitForReconciliation(reconciliationAt);
        AnalysisJob afterReviewedMatches = matching.waitForMatchReview(reconciliationAt)
                .waitForReconciliation(reconciliationAt.plusSeconds(1));

        assertWaitingAtReconciliation(matching, afterAutomaticMatches, reconciliationAt);
        assertWaitingAtReconciliation(matching, afterReviewedMatches, reconciliationAt.plusSeconds(1));
        assertThrows(IllegalStateException.class,
                () -> afterAutomaticMatches.waitForReconciliation(reconciliationAt.plusSeconds(2)));
        AnalysisJob awaitingConfirmation = AnalysisJob.waitingForUser(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThrows(IllegalStateException.class,
                () -> awaitingConfirmation.waitForReconciliation(NOW.plusSeconds(1)));
    }

    @Test
    void awaitsReportGenerationWithoutStartingAnAttemptOrChangingAttemptTimestamps() {
        Instant reconciliationAt = NOW.plusSeconds(5);
        AnalysisJob reconciling = new AnalysisJob(
                UUID.randomUUID(), UUID.randomUUID(), AnalysisJobStatus.WAITING_FOR_USER,
                AnalysisStatus.RECONCILING, 2, true, "PREVIOUS_ERROR", "Previous attempt failed",
                NOW.plusSeconds(1), NOW.plusSeconds(2), NOW, reconciliationAt);
        Instant reportAt = NOW.plusSeconds(6);

        AnalysisJob reportGeneration = reconciling.awaitReportGeneration(reportAt);

        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, reportGeneration.status());
        assertEquals(AnalysisStatus.GENERATING_REPORT, reportGeneration.currentStage());
        assertEquals(0, reportGeneration.attemptCount());
        assertFalse(reportGeneration.retryable());
        assertNull(reportGeneration.lastErrorCode());
        assertNull(reportGeneration.lastErrorMessage());
        assertNull(reportGeneration.startedAt());
        assertNull(reportGeneration.completedAt());
        assertEquals(reconciling.createdAt(), reportGeneration.createdAt());
        assertEquals(reportAt, reportGeneration.updatedAt());
    }

    @Test
    void completesReportGenerationOnlyAfterClaimedWorkerAttempt() {
        AnalysisJob reportGeneration = matchingJob()
                .waitForReconciliation(NOW.plusSeconds(5))
                .queueReportGeneration(NOW.plusSeconds(6))
                .startQueuedAttempt(NOW.plusSeconds(7), 3);
        Instant generatedAt = NOW.plusSeconds(8);

        AnalysisJob completed = reportGeneration.completeReportGeneration(generatedAt);

        assertEquals(AnalysisJobStatus.COMPLETED, completed.status());
        assertEquals(AnalysisStatus.COMPLETED, completed.currentStage());
        assertEquals(generatedAt, completed.completedAt());
        assertEquals(generatedAt, completed.updatedAt());
        assertEquals(reportGeneration.attemptCount(), completed.attemptCount());
        assertFalse(completed.retryable());
        assertNull(completed.lastErrorCode());
        assertNull(completed.lastErrorMessage());
        assertEquals(reportGeneration.startedAt(), completed.startedAt());
        assertEquals(reportGeneration.createdAt(), completed.createdAt());
    }

    @Test
    void rejectsReportCompletionFromWrongStatusOrStageAndInvalidTime() {
        AnalysisJob reportGeneration = matchingJob()
                .waitForReconciliation(NOW.plusSeconds(5))
                .awaitReportGeneration(NOW.plusSeconds(6));

        assertThrows(NullPointerException.class, () -> reportGeneration.completeReportGeneration(null));
        assertThrows(IllegalStateException.class,
                () -> reportGeneration.completeReportGeneration(NOW.plusSeconds(7)));
        assertThrows(IllegalStateException.class,
                () -> matchingJob().completeReportGeneration(NOW.plusSeconds(5)));
        AnalysisJob runningReport = matchingJob().waitForReconciliation(NOW.plusSeconds(5))
                .queueReportGeneration(NOW.plusSeconds(6))
                .startQueuedAttempt(NOW.plusSeconds(7), 3);
        assertThrows(IllegalArgumentException.class,
                () -> runningReport.completeReportGeneration(runningReport.updatedAt().minusSeconds(1)));
        assertThrows(IllegalStateException.class,
                () -> runningReport.completeReportGeneration(NOW.plusSeconds(9))
                        .completeReportGeneration(NOW.plusSeconds(10)));

        AnalysisJob queuedAtReportStage = new AnalysisJob(
                UUID.randomUUID(), UUID.randomUUID(), AnalysisJobStatus.QUEUED,
                AnalysisStatus.GENERATING_REPORT, 0, false, null, null, null, null, NOW, NOW);
        assertThrows(IllegalStateException.class,
                () -> queuedAtReportStage.completeReportGeneration(NOW.plusSeconds(1)));
    }

    @Test
    void rejectsReportGenerationWaitFromWrongStatusOrStageAndInvalidTime() {
        AnalysisJob reconciling = AnalysisJob.waitingForUser(UUID.randomUUID(), UUID.randomUUID(), NOW)
                .awaitMoreUploads(NOW.plusSeconds(1))
                .waitForUserAtClassification(NOW.plusSeconds(2))
                .waitForExtractionConfirmation(NOW.plusSeconds(3))
                .waitForMatching(NOW.plusSeconds(4))
                .waitForReconciliation(NOW.plusSeconds(5));

        assertThrows(NullPointerException.class, () -> reconciling.awaitReportGeneration(null));
        assertThrows(IllegalArgumentException.class,
                () -> reconciling.awaitReportGeneration(reconciling.updatedAt().minusSeconds(1)));
        assertThrows(IllegalStateException.class,
                () -> reconciling.awaitReportGeneration(NOW.plusSeconds(6)).awaitReportGeneration(NOW.plusSeconds(7)));

        AnalysisJob wrongStage = AnalysisJob.waitingForUser(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThrows(IllegalStateException.class, () -> wrongStage.awaitReportGeneration(NOW.plusSeconds(1)));
        AnalysisJob wrongStatus = new AnalysisJob(
                UUID.randomUUID(), UUID.randomUUID(), AnalysisJobStatus.QUEUED,
                AnalysisStatus.RECONCILING, 0, false, null, null, null, null, NOW, NOW);
        assertThrows(IllegalStateException.class, () -> wrongStatus.awaitReportGeneration(NOW.plusSeconds(1)));
    }

    private static void assertWaitingAtReconciliation(AnalysisJob before, AnalysisJob after, Instant now) {
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, after.status());
        assertEquals(AnalysisStatus.RECONCILING, after.currentStage());
        assertEquals(before.attemptCount(), after.attemptCount());
        assertFalse(after.retryable());
        assertEquals(before.startedAt(), after.startedAt());
        assertEquals(before.completedAt(), after.completedAt());
        assertEquals(before.createdAt(), after.createdAt());
        assertEquals(now, after.updatedAt());
    }

    private static AnalysisJob matchingJob() {
        return AnalysisJob.waitingForUser(UUID.randomUUID(), UUID.randomUUID(), NOW)
                .awaitMoreUploads(NOW.plusSeconds(1))
                .waitForUserAtClassification(NOW.plusSeconds(2))
                .waitForExtractionConfirmation(NOW.plusSeconds(3))
                .waitForMatching(NOW.plusSeconds(4));
    }

    private static AnalysisJob queuedJob(AnalysisStatus stage, int attemptCount) {
        return new AnalysisJob(
                UUID.randomUUID(), UUID.randomUUID(), AnalysisJobStatus.QUEUED,
                stage, attemptCount, false, null, null, null, null, NOW, NOW.plusSeconds(1));
    }

    private static AnalysisJob runningJob(AnalysisStatus stage, int attemptCount) {
        return new AnalysisJob(
                UUID.randomUUID(), UUID.randomUUID(), AnalysisJobStatus.RUNNING,
                stage, attemptCount, false, null, null,
                NOW.plusSeconds(1), null, NOW, NOW.plusSeconds(2));
    }

    private static void assertQueuedStage(AnalysisJob job, AnalysisStatus stage, int attempts, Instant updatedAt) {
        assertEquals(AnalysisJobStatus.QUEUED, job.status());
        assertEquals(stage, job.currentStage());
        assertEquals(attempts, job.attemptCount());
        assertFalse(job.retryable());
        assertNull(job.lastErrorCode());
        assertNull(job.lastErrorMessage());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
        assertEquals(updatedAt, job.updatedAt());
    }

    private static void assertRunningStage(AnalysisJob job, AnalysisStatus stage, int attempts, Instant startedAt) {
        assertEquals(AnalysisJobStatus.RUNNING, job.status());
        assertEquals(stage, job.currentStage());
        assertEquals(attempts, job.attemptCount());
        assertFalse(job.retryable());
        assertNull(job.lastErrorCode());
        assertNull(job.lastErrorMessage());
        assertEquals(startedAt, job.startedAt());
        assertNull(job.completedAt());
        assertEquals(startedAt, job.updatedAt());
    }

    private static void assertWaitingStage(AnalysisJob job, AnalysisStatus stage, Instant updatedAt) {
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(stage, job.currentStage());
        assertEquals(0, job.attemptCount());
        assertFalse(job.retryable());
        assertNull(job.lastErrorCode());
        assertNull(job.lastErrorMessage());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
        assertEquals(updatedAt, job.updatedAt());
    }

    private static void assertFailedStage(
            AnalysisJob job, AnalysisStatus stage, int attempts, boolean retryable, Instant updatedAt) {
        assertEquals(AnalysisJobStatus.FAILED, job.status());
        assertEquals(stage, job.currentStage());
        assertEquals(attempts, job.attemptCount());
        assertEquals(retryable, job.retryable());
        assertEquals(NOW.plusSeconds(1), job.startedAt());
        assertNull(job.completedAt());
        assertTrue(job.lastErrorCode() != null && !job.lastErrorCode().isBlank());
        assertTrue(job.lastErrorMessage() != null && !job.lastErrorMessage().isBlank());
        assertEquals(updatedAt, job.updatedAt());
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
        AnalysisJob synchronouslyCompleted = new AnalysisJob(
                jobId, analysisId, AnalysisJobStatus.COMPLETED,
                AnalysisStatus.COMPLETED, 0, false, null, null, null, NOW, NOW, NOW);
        assertNull(synchronouslyCompleted.startedAt());
        assertEquals(NOW, synchronouslyCompleted.completedAt());
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisJob(jobId, analysisId, AnalysisJobStatus.COMPLETED,
                        AnalysisStatus.COMPLETED, 0, false, null, null, null,
                        NOW.minusSeconds(1), NOW, NOW));
    }
}
