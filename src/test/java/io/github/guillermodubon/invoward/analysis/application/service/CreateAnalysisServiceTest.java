package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateAnalysisServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    private final CreateAnalysisTransaction transaction = mock(CreateAnalysisTransaction.class);
    private CreateAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new CreateAnalysisService(
                transaction, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAnalysisAndWaitingJobWithTheSameClockInstantAndRequestedValues() {
        RegisteredUserOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        PriceTolerance tolerance = new PriceTolerance(new BigDecimal("3"), new BigDecimal("12.3400"));
        when(transaction.create(any(Analysis.class), any(AnalysisJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Analysis result = service.create(owner, tolerance);

        ArgumentCaptor<Analysis> analysisCaptor = ArgumentCaptor.forClass(Analysis.class);
        ArgumentCaptor<AnalysisJob> jobCaptor = ArgumentCaptor.forClass(AnalysisJob.class);
        verify(transaction).create(analysisCaptor.capture(), jobCaptor.capture());
        Analysis created = analysisCaptor.getValue();
        AnalysisJob job = jobCaptor.getValue();

        assertEquals(created, result);
        assertNotNull(created.id());
        assertEquals(owner, created.owner());
        assertEquals(tolerance, created.priceTolerance());
        assertEquals(AnalysisStatus.CREATED, created.status());
        assertEquals(AnalysisReviewStatus.PENDING, created.reviewStatus());
        assertNull(created.reconciliationStatus());
        assertFalse(created.retryable());
        assertNull(created.expiresAt());
        assertEquals(NOW, created.createdAt());
        assertEquals(NOW, created.updatedAt());

        assertNotNull(job.id());
        assertNotEquals(created.id(), job.id());
        assertEquals(created.id(), job.analysisId());
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
}
