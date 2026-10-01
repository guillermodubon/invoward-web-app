package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetAnalysisStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private final GetAnalysisService getAnalysisService = mock(GetAnalysisService.class);
    private final AnalysisJobRepository jobRepository = mock(AnalysisJobRepository.class);
    private GetAnalysisStatusService service;

    @BeforeEach
    void setUp() {
        service = new GetAnalysisStatusService(getAnalysisService, jobRepository);
    }

    @Test
    void returnsOnlySafeAnalysisAndJobStatusFields() {
        UUID analysisId = UUID.randomUUID();
        RegisteredUserOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        Analysis analysis = failedAnalysis(analysisId, owner);
        AnalysisJob job = new AnalysisJob(
                UUID.randomUUID(), analysisId, AnalysisJobStatus.FAILED, AnalysisStatus.FAILED,
                2, false, "INTERNAL_PROVIDER_ERROR", "provider response must stay private",
                NOW, NOW, NOW, NOW);
        when(getAnalysisService.get(analysisId, owner)).thenReturn(analysis);
        when(jobRepository.findByAnalysisId(analysisId)).thenReturn(Optional.of(job));

        AnalysisStatusSnapshot result = service.getStatus(analysisId, owner);

        assertEquals(analysisId, result.analysisId());
        assertEquals(AnalysisStatus.FAILED, result.status());
        assertEquals(AnalysisJobStatus.FAILED, result.jobStatus());
        assertEquals(AnalysisStatus.FAILED, result.currentStage());
        assertEquals(analysis.retryable(), result.retryable());
        assertEquals(analysis.failureCode(), result.failureCode());
        assertEquals(analysis.failureUserMessage(), result.failureMessage());
        assertEquals(analysis.updatedAt(), result.updatedAt());
        verify(jobRepository).findByAnalysisId(analysisId);
    }

    @Test
    void doesNotQueryJobWhenOwnerScopedAnalysisLookupFails() {
        UUID analysisId = UUID.randomUUID();
        RegisteredUserOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        when(getAnalysisService.get(analysisId, owner))
                .thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class,
                () -> service.getStatus(analysisId, owner));

        verify(jobRepository, never()).findByAnalysisId(analysisId);
    }

    @Test
    void treatsAnAnalysisWithoutItsRequiredJobAsAnInvariantFailure() {
        UUID analysisId = UUID.randomUUID();
        RegisteredUserOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        when(getAnalysisService.get(analysisId, owner)).thenReturn(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), NOW));
        when(jobRepository.findByAnalysisId(analysisId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.getStatus(analysisId, owner));
    }

    private static Analysis failedAnalysis(UUID id, RegisteredUserOwner owner) {
        return new Analysis(
                id, owner, AnalysisStatus.FAILED, AnalysisReviewStatus.PENDING, null,
                null, null, null, null, null, null, null, null, null,
                PriceTolerance.exactMatch(), false, "PROCESSING_FAILED",
                "Analysis processing failed.", 0, NOW, null, NOW, NOW);
    }
}
