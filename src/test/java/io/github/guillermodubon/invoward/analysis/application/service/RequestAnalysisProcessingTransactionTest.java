package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisProcessingNotAllowedException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestAnalysisProcessingTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final GuestSessionOwner OWNER = new GuestSessionOwner(UUID.randomUUID(), NOW.plusSeconds(3600));

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final AnalysisJobRepository jobRepository = mock(AnalysisJobRepository.class);
    private final RequestAnalysisProcessingTransaction transaction = new RequestAnalysisProcessingTransaction(
            analysisRepository, jobRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void queuesEligibleLegacyReconciliationStageAndReturnsCurrentStatus() {
        Analysis analysis = analysisAtReconciliation();
        AnalysisJob waitingJob = waitingAtReconciliation();
        AnalysisJob queuedJob = waitingJob.queueLegacyMachineStage(NOW);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
        when(jobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(waitingJob));
        when(jobRepository.update(queuedJob)).thenReturn(queuedJob);

        var snapshot = transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING);

        assertEquals(AnalysisStatus.RECONCILING, snapshot.status());
        assertEquals(AnalysisJobStatus.QUEUED, snapshot.jobStatus());
        assertEquals(AnalysisStatus.RECONCILING, snapshot.currentStage());
        assertEquals(queuedJob.updatedAt(), snapshot.updatedAt());
        verify(analysisRepository, never()).update(org.mockito.ArgumentMatchers.any());
        verify(jobRepository).update(queuedJob);
    }

    @Test
    void keepsAnAlreadyQueuedStageUnchanged() {
        Analysis analysis = analysisAtReconciliation();
        AnalysisJob queued = waitingAtReconciliation().queueLegacyMachineStage(NOW.minusSeconds(1));
        stubOwned(analysis, queued);

        var snapshot = transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING);

        assertEquals(AnalysisJobStatus.QUEUED, snapshot.jobStatus());
        verify(jobRepository, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void keepsAnAlreadyRunningStageUnchanged() {
        Analysis analysis = analysisAtReconciliation();
        AnalysisJob running = waitingAtReconciliation()
                .queueLegacyMachineStage(NOW.minusSeconds(2))
                .startQueuedAttempt(NOW.minusSeconds(1), 3);
        stubOwned(analysis, running);

        var snapshot = transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING);

        assertEquals(AnalysisJobStatus.RUNNING, snapshot.jobStatus());
        verify(jobRepository, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsPersistedStatusWhenReconciliationHasAlreadyAdvanced() {
        Analysis analysis = analysisAtReconciliation()
                .completeReconciliation(AnalysisReconciliationStatus.MATCHED, null, NOW.minusSeconds(2));
        AnalysisJob job = waitingAtReconciliation().awaitReportGeneration(NOW.minusSeconds(2));
        stubOwned(analysis, job);

        var snapshot = transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING);

        assertEquals(AnalysisStatus.GENERATING_REPORT, snapshot.status());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, snapshot.jobStatus());
        verify(jobRepository, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAUserGateBeforeReconciliation() {
        Analysis analysis = analysisAtMatchReview();
        AnalysisJob job = waitingAtMatchReview();
        stubOwned(analysis, job);

        assertThrows(AnalysisProcessingNotAllowedException.class,
                () -> transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING));

        verify(jobRepository, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ownerScopedAnalysisLookupOccursBeforeJobLookup() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING));

        verifyNoInteractions(jobRepository);
    }

    @Test
    void rejectsFailedOrUnsupportedStageRequests() {
        assertThrows(AnalysisProcessingNotAllowedException.class,
                () -> transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.MATCHING));
        verifyNoInteractions(analysisRepository, jobRepository);
    }

    private void stubOwned(Analysis analysis, AnalysisJob job) {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
        when(jobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(job));
    }

    private static Analysis analysisAtMatchReview() {
        Instant start = NOW.minusSeconds(60);
        return Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), start)
                .transitionToUploading(start.plusSeconds(1))
                .beginClassification(start.plusSeconds(2))
                .awaitExtractionConfirmation(start.plusSeconds(3))
                .confirmExtraction("QUOTE", "Q-1", "I-1", "Vendor", "USD",
                        BigDecimal.TEN, BigDecimal.TEN, start.plusSeconds(4))
                .awaitMatchReview(start.plusSeconds(5));
    }

    private static Analysis analysisAtReconciliation() {
        return analysisAtMatchReview().beginReconciliation(NOW.minusSeconds(54));
    }

    private static AnalysisJob waitingAtMatchReview() {
        Instant start = NOW.minusSeconds(60);
        return AnalysisJob.waitingForUser(UUID.randomUUID(), ANALYSIS_ID, start)
                .awaitMoreUploads(start.plusSeconds(1))
                .waitForUserAtClassification(start.plusSeconds(2))
                .waitForExtractionConfirmation(start.plusSeconds(3))
                .waitForMatching(start.plusSeconds(4))
                .waitForMatchReview(start.plusSeconds(5));
    }

    private static AnalysisJob waitingAtReconciliation() {
        return waitingAtMatchReview().waitForReconciliation(NOW.minusSeconds(54));
    }
}
