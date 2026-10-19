package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reporting.application.exception.AnalysisReportNotAllowedException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportConflictException;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersistGeneratedReportTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final GeneratedReportRepository generatedReportRepository = mock(GeneratedReportRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private PersistGeneratedReportTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new PersistGeneratedReportTransaction(
                analysisRepository, analysisJobRepository, generatedReportRepository, clock);
    }

    @Test
    void persistsMetadataAndCompletesBothWorkflowRecordsAtomically() {
        Analysis analysis = reportReadyAnalysis(new RegisteredUserOwner(UUID.randomUUID()), NOW.minusSeconds(10));
        AnalysisJob job = reportReadyJob(analysis.id(), NOW.minusSeconds(10));
        GeneratedReport candidate = report(analysis.id(), null, NOW);
        when(analysisRepository.findOwnedByIdForUpdate(analysis.id(), analysis.owner(), NOW))
                .thenReturn(Optional.of(analysis));
        when(generatedReportRepository.findByAnalysisId(analysis.id())).thenReturn(Optional.empty());
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id())).thenReturn(Optional.of(job));
        when(generatedReportRepository.create(candidate)).thenReturn(candidate);
        when(analysisRepository.update(any(Analysis.class))).thenAnswer(call -> call.getArgument(0));
        when(analysisJobRepository.update(any(AnalysisJob.class))).thenAnswer(call -> call.getArgument(0));

        var result = transaction.persist(analysis.owner(), candidate);

        assertTrue(result.createdByThisCall());
        assertEquals(candidate, result.report());
        var ordered = inOrder(analysisRepository, generatedReportRepository, analysisJobRepository);
        ordered.verify(analysisRepository).findOwnedByIdForUpdate(analysis.id(), analysis.owner(), NOW);
        ordered.verify(generatedReportRepository).findByAnalysisId(analysis.id());
        ordered.verify(analysisJobRepository).findJobByAnalysisIdForUpdate(analysis.id());
        ordered.verify(generatedReportRepository).create(candidate);
        ordered.verify(analysisRepository).update(analysis.completeReportGeneration(NOW));
        ordered.verify(analysisJobRepository).update(job.completeReportGeneration(NOW));
    }

    @Test
    void returnsExistingReportAsConcurrentWinnerWithoutRewritingWorkflow() {
        Analysis analysis = reportReadyAnalysis(new RegisteredUserOwner(UUID.randomUUID()), NOW.minusSeconds(10));
        GeneratedReport winner = report(analysis.id(), null, NOW.minusSeconds(1));
        when(analysisRepository.findOwnedByIdForUpdate(analysis.id(), analysis.owner(), NOW))
                .thenReturn(Optional.of(analysis));
        when(generatedReportRepository.findByAnalysisId(analysis.id())).thenReturn(Optional.of(winner));

        var result = transaction.persist(analysis.owner(), report(analysis.id(), null, NOW));

        assertEquals(winner, result.report());
        assertFalse(result.createdByThisCall());
        verifyNoInteractions(analysisJobRepository);
        verify(generatedReportRepository, never()).create(any());
        verify(analysisRepository, never()).update(any());
    }

    @Test
    void authorizesOwnerBeforeAccessingReportOrJobRepositories() {
        UUID analysisId = UUID.randomUUID();
        AnalysisOwner wrongOwner = new RegisteredUserOwner(UUID.randomUUID());
        GeneratedReport candidate = report(analysisId, null, NOW);
        when(analysisRepository.findOwnedByIdForUpdate(analysisId, wrongOwner, NOW))
                .thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> transaction.persist(wrongOwner, candidate));

        verifyNoInteractions(generatedReportRepository, analysisJobRepository);
    }

    @Test
    void rejectsAnalysisOutsideGenerationStageWithoutWriting() {
        Analysis analysis = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()),
                PriceTolerance.exactMatch(), NOW.minusSeconds(10));
        when(analysisRepository.findOwnedByIdForUpdate(analysis.id(), analysis.owner(), NOW))
                .thenReturn(Optional.of(analysis));
        when(generatedReportRepository.findByAnalysisId(analysis.id())).thenReturn(Optional.empty());

        assertThrows(AnalysisReportNotAllowedException.class,
                () -> transaction.persist(analysis.owner(), report(analysis.id(), null, NOW)));

        verifyNoInteractions(analysisJobRepository);
        verify(generatedReportRepository, never()).create(any());
        verify(analysisRepository, never()).update(any());
    }

    @Test
    void completedAnalysisWithoutReportIsAConflict() {
        Analysis analysis = reportReadyAnalysis(new RegisteredUserOwner(UUID.randomUUID()), NOW.minusSeconds(10))
                .completeReportGeneration(NOW.minusSeconds(1));
        when(analysisRepository.findOwnedByIdForUpdate(analysis.id(), analysis.owner(), NOW))
                .thenReturn(Optional.of(analysis));
        when(generatedReportRepository.findByAnalysisId(analysis.id())).thenReturn(Optional.empty());

        assertThrows(ReportConflictException.class,
                () -> transaction.persist(analysis.owner(), report(analysis.id(), null, NOW)));

        verifyNoInteractions(analysisJobRepository);
        verify(generatedReportRepository, never()).create(any());
    }

    @Test
    void requiresCandidateExpiryToMatchAnalysisOwnerLifetime() {
        Instant expiry = NOW.plusSeconds(3600);
        AnalysisOwner owner = new GuestSessionOwner(UUID.randomUUID(), expiry);
        Analysis analysis = reportReadyAnalysis(owner, NOW.minusSeconds(10));
        AnalysisJob job = reportReadyJob(analysis.id(), NOW.minusSeconds(10));
        when(analysisRepository.findOwnedByIdForUpdate(analysis.id(), owner, NOW))
                .thenReturn(Optional.of(analysis));
        when(generatedReportRepository.findByAnalysisId(analysis.id())).thenReturn(Optional.empty());
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id())).thenReturn(Optional.of(job));

        assertThrows(ReportConflictException.class,
                () -> transaction.persist(owner, report(analysis.id(), expiry.minusSeconds(1), NOW)));

        verify(generatedReportRepository, never()).create(any());
    }

    @Test
    void rejectsJobOutsideReportGenerationStageWithoutWriting() {
        Analysis analysis = reportReadyAnalysis(new RegisteredUserOwner(UUID.randomUUID()), NOW.minusSeconds(10));
        AnalysisJob wrongStage = new AnalysisJob(
                UUID.randomUUID(), analysis.id(), AnalysisJobStatus.QUEUED,
                AnalysisStatus.GENERATING_REPORT, 0, false, null, null, null, null,
                analysis.createdAt(), analysis.updatedAt());
        when(analysisRepository.findOwnedByIdForUpdate(analysis.id(), analysis.owner(), NOW))
                .thenReturn(Optional.of(analysis));
        when(generatedReportRepository.findByAnalysisId(analysis.id())).thenReturn(Optional.empty());
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id())).thenReturn(Optional.of(wrongStage));

        assertThrows(ReportConflictException.class,
                () -> transaction.persist(analysis.owner(), report(analysis.id(), null, NOW)));

        verify(generatedReportRepository, never()).create(any());
        verify(analysisRepository, never()).update(any());
        verify(analysisJobRepository, never()).update(any());
    }

    @Test
    void rejectsWaitingReportJobWithoutWriting() {
        Analysis analysis = reportReadyAnalysis(new RegisteredUserOwner(UUID.randomUUID()), NOW.minusSeconds(10));
        AnalysisJob waiting = new AnalysisJob(
                UUID.randomUUID(), analysis.id(), AnalysisJobStatus.WAITING_FOR_USER,
                AnalysisStatus.GENERATING_REPORT, 0, false, null, null, null, null,
                analysis.createdAt(), analysis.updatedAt());
        when(analysisRepository.findOwnedByIdForUpdate(analysis.id(), analysis.owner(), NOW))
                .thenReturn(Optional.of(analysis));
        when(generatedReportRepository.findByAnalysisId(analysis.id())).thenReturn(Optional.empty());
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id())).thenReturn(Optional.of(waiting));

        assertThrows(ReportConflictException.class,
                () -> transaction.persist(analysis.owner(), report(analysis.id(), null, NOW)));

        verify(generatedReportRepository, never()).create(any());
        verify(analysisRepository, never()).update(any());
        verify(analysisJobRepository, never()).update(any());
    }

    @Test
    void rejectsGeneratedAtEarlierThanLockedWorkflowTimestamps() {
        Analysis analysis = reportReadyAnalysis(new RegisteredUserOwner(UUID.randomUUID()), NOW.minusSeconds(10));
        AnalysisJob job = reportReadyJob(analysis.id(), NOW.minusSeconds(10));
        when(analysisRepository.findOwnedByIdForUpdate(analysis.id(), analysis.owner(), NOW))
                .thenReturn(Optional.of(analysis));
        when(generatedReportRepository.findByAnalysisId(analysis.id())).thenReturn(Optional.empty());
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id())).thenReturn(Optional.of(job));

        assertThrows(ReportConflictException.class,
                () -> transaction.persist(analysis.owner(), report(analysis.id(), null, NOW.minusSeconds(5))));

        verify(generatedReportRepository, never()).create(any());
        verify(analysisRepository, never()).update(any());
        verify(analysisJobRepository, never()).update(any());
    }

    private static Analysis reportReadyAnalysis(AnalysisOwner owner, Instant start) {
        return Analysis.create(UUID.randomUUID(), owner, PriceTolerance.exactMatch(), start)
                .transitionToUploading(start.plusSeconds(1))
                .markClassifying(start.plusSeconds(2))
                .awaitExtractionConfirmation(start.plusSeconds(3))
                .confirmExtraction("QUOTE", "Q-1", "I-1", "Vendor", "USD",
                        new BigDecimal("100.0000"), new BigDecimal("100.0000"), start.plusSeconds(4))
                .beginReconciliation(start.plusSeconds(5))
                .completeReconciliation(AnalysisReconciliationStatus.MATCHED,
                        BigDecimal.ZERO, start.plusSeconds(6));
    }

    private static AnalysisJob reportReadyJob(UUID analysisId, Instant start) {
        return AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, start)
                .awaitMoreUploads(start.plusSeconds(1))
                .waitForUserAtClassification(start.plusSeconds(2))
                .waitForExtractionConfirmation(start.plusSeconds(3))
                .waitForMatching(start.plusSeconds(4))
                .waitForReconciliation(start.plusSeconds(5))
                .queueReportGeneration(start.plusSeconds(6))
                .startQueuedAttempt(start.plusSeconds(7), 3);
    }

    private static GeneratedReport report(UUID analysisId, Instant expiresAt, Instant generatedAt) {
        return new GeneratedReport(UUID.randomUUID(), analysisId,
                "reports/" + UUID.randomUUID() + ".pdf", "application/pdf", 123L,
                "a".repeat(64), expiresAt, generatedAt);
    }
}
