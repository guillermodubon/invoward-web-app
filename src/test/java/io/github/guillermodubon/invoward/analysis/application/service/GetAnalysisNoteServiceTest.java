package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisNoteReadModel;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisNoteRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisNote;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetAnalysisNoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final AnalysisNoteRepository noteRepository = mock(AnalysisNoteRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final GetAnalysisNoteService service =
            new GetAnalysisNoteService(analysisRepository, noteRepository, clock);

    @Test
    void returnsExistingNoteOnlyAfterOwnerScopedParentLookup() {
        UUID analysisId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        Analysis analysis = Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), NOW);
        AnalysisNote note = AnalysisNote.create(analysisId, "Private supplier note", NOW.minusSeconds(10));
        when(analysisRepository.findOwnedById(analysisId, owner, NOW)).thenReturn(Optional.of(analysis));
        when(noteRepository.findByAnalysisId(analysisId)).thenReturn(Optional.of(note));

        AnalysisNoteReadModel result = service.get(analysisId, userId);

        assertEquals(analysisId, result.analysisId());
        assertEquals("Private supplier note", result.text());
        assertEquals(0L, result.version());
        assertEquals(note.createdAt(), result.createdAt());
        assertEquals(note.updatedAt(), result.updatedAt());
        var ordered = inOrder(analysisRepository, noteRepository);
        ordered.verify(analysisRepository).findOwnedById(analysisId, owner, NOW);
        ordered.verify(noteRepository).findByAnalysisId(analysisId);
        verifyNoWrites();
    }

    @Test
    void returnsEmptyProjectionWithoutCreatingAPersistedPlaceholderWhenNoteIsAbsent() {
        UUID analysisId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        when(analysisRepository.findOwnedById(analysisId, owner, NOW)).thenReturn(Optional.of(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), NOW)));
        when(noteRepository.findByAnalysisId(analysisId)).thenReturn(Optional.empty());

        AnalysisNoteReadModel result = service.get(analysisId, userId);

        assertEquals(analysisId, result.analysisId());
        assertNull(result.text());
        assertNull(result.version());
        assertNull(result.createdAt());
        assertNull(result.updatedAt());
        verifyNoWrites();
    }

    @Test
    void foreignOrMissingRegisteredAnalysisFailsBeforeAnyNoteLookup() {
        UUID analysisId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        when(analysisRepository.findOwnedById(analysisId, owner, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> service.get(analysisId, userId));

        verify(analysisRepository).findOwnedById(analysisId, owner, NOW);
        verify(noteRepository, never()).findByAnalysisId(analysisId);
        verifyNoWrites();
    }

    @Test
    void guestOwnedParentReturnedByRepositoryIsStillTreatedAsNotFound() {
        UUID analysisId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RegisteredUserOwner requestedOwner = new RegisteredUserOwner(userId);
        Analysis guestAnalysis = Analysis.create(
                analysisId,
                new GuestSessionOwner(UUID.randomUUID(), NOW.plusSeconds(3600)),
                PriceTolerance.exactMatch(),
                NOW);
        when(analysisRepository.findOwnedById(analysisId, requestedOwner, NOW))
                .thenReturn(Optional.of(guestAnalysis));

        assertThrows(AnalysisNotFoundException.class, () -> service.get(analysisId, userId));

        verify(noteRepository, never()).findByAnalysisId(analysisId);
        verifyNoWrites();
    }

    @Test
    void rejectsMissingIdentityOrAnalysisIdBeforeRepositoryAccess() {
        assertThrows(NullPointerException.class, () -> service.get(UUID.randomUUID(), null));
        assertThrows(NullPointerException.class, () -> service.get(null, UUID.randomUUID()));

        verifyNoInteractions();
    }

    @Test
    void serviceIsMarkedReadOnly() throws NoSuchMethodException {
        Transactional transaction = GetAnalysisNoteService.class.getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly());
    }

    private void verifyNoWrites() {
        verify(analysisRepository, never()).update(org.mockito.ArgumentMatchers.any(Analysis.class));
        verify(noteRepository, never()).create(org.mockito.ArgumentMatchers.any(AnalysisNote.class));
        verify(noteRepository, never()).update(org.mockito.ArgumentMatchers.any(AnalysisNote.class));
        verify(noteRepository, never()).delete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    private void verifyNoInteractions() {
        verify(analysisRepository, never()).findOwnedById(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(noteRepository, never()).findByAnalysisId(org.mockito.ArgumentMatchers.any());
    }
}
