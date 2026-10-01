package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetAnalysisServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private final AnalysisRepository repository = mock(AnalysisRepository.class);
    private GetAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new GetAnalysisService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsTheAnalysisFromTheOwnerScopedQueryAtTheCurrentClockInstant() {
        UUID analysisId = UUID.randomUUID();
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        Analysis analysis = Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), NOW);
        when(repository.findOwnedById(analysisId, owner, NOW)).thenReturn(Optional.of(analysis));

        assertSame(analysis, service.get(analysisId, owner));
        verify(repository).findOwnedById(analysisId, owner, NOW);
    }

    @Test
    void mapsMissingOrNotOwnedAnalysisToTheSameSafeException() {
        UUID analysisId = UUID.randomUUID();
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        when(repository.findOwnedById(analysisId, owner, NOW)).thenReturn(Optional.empty());

        AnalysisNotFoundException exception = assertThrows(
                AnalysisNotFoundException.class, () -> service.get(analysisId, owner));

        assertEquals("Analysis was not found.", exception.getMessage());
    }
}
