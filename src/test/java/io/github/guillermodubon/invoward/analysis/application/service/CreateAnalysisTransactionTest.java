package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateAnalysisTransactionTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private CreateAnalysisTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new CreateAnalysisTransaction(analysisRepository, analysisJobRepository);
    }

    @Test
    void persistsAnalysisBeforeItsInitialJobAndReturnsPersistedAnalysis() {
        Analysis analysis = analysis(UUID.randomUUID());
        AnalysisJob initialJob = AnalysisJob.waitingForUser(UUID.randomUUID(), analysis.id(), NOW);
        Analysis persisted = analysis;
        when(analysisRepository.create(analysis)).thenReturn(persisted);

        Analysis result = transaction.create(analysis, initialJob);

        assertEquals(persisted, result);
        InOrder order = inOrder(analysisRepository, analysisJobRepository);
        order.verify(analysisRepository).create(analysis);
        order.verify(analysisJobRepository).create(initialJob);
    }

    @Test
    void rejectsAJobThatDoesNotBelongToTheAnalysisBeforePersistingEither() {
        Analysis analysis = analysis(UUID.randomUUID());
        AnalysisJob unrelatedJob = AnalysisJob.waitingForUser(UUID.randomUUID(), UUID.randomUUID(), NOW);

        assertThrows(IllegalArgumentException.class,
                () -> transaction.create(analysis, unrelatedJob));

        verify(analysisRepository, never()).create(analysis);
        verify(analysisJobRepository, never()).create(unrelatedJob);
    }

    private static Analysis analysis(UUID id) {
        return Analysis.create(id,
                new RegisteredUserOwner(UUID.randomUUID()), PriceTolerance.exactMatch(), NOW);
    }
}
