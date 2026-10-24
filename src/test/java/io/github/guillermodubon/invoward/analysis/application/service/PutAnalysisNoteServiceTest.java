package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.exception.InvalidAnalysisNoteException;
import io.github.guillermodubon.invoward.analysis.application.exception.NoteVersionConflictException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

class PutAnalysisNoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final RegisteredUserOwner OWNER = new RegisteredUserOwner(USER_ID);

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final AnalysisNoteRepository noteRepository = mock(AnalysisNoteRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PutAnalysisNoteService service =
            new PutAnalysisNoteService(analysisRepository, noteRepository, clock);

    @BeforeEach
    void authorizeParent() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW)));
    }

    @Test
    void createsNoteOnlyAfterLockingRegisteredOwnedParent() {
        when(noteRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.empty());
        when(noteRepository.create(any(AnalysisNote.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AnalysisNoteReadModel result = service.put(ANALYSIS_ID, USER_ID, "  Supplier approved.  ", null);

        assertEquals("Supplier approved.", result.text());
        assertEquals(0L, result.version());
        assertEquals(NOW, result.createdAt());
        var order = inOrder(analysisRepository, noteRepository);
        order.verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW);
        order.verify(noteRepository).findByAnalysisId(ANALYSIS_ID);
        order.verify(noteRepository).create(new AnalysisNote(ANALYSIS_ID, "Supplier approved.", 0, NOW, NOW));
        verifyNoAnalysisMutation();
    }

    @Test
    void replacesTextPreservingUnicodeAndInternalLineBreaksAndNormalizingCrLf() {
        AnalysisNote existing = AnalysisNote.create(ANALYSIS_ID, "Old", NOW.minusSeconds(30));
        when(noteRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(existing));
        when(noteRepository.update(any(AnalysisNote.class))).thenAnswer(invocation -> {
            AnalysisNote requested = invocation.getArgument(0);
            return new AnalysisNote(requested.analysisId(), requested.text(), 1,
                    requested.createdAt(), requested.updatedAt());
        });

        AnalysisNoteReadModel result = service.put(ANALYSIS_ID, USER_ID, "  Café 😀\r\nline\tinside  ", 0L);

        assertEquals("Café 😀\nline\tinside", result.text());
        assertEquals(1L, result.version());
        assertEquals(NOW.minusSeconds(30), result.createdAt());
        assertEquals(NOW, result.updatedAt());
        verifyNoAnalysisMutation();
    }

    @Test
    void unchangedTextWithCurrentVersionIsNoOpAndPreservesMetadata() {
        AnalysisNote existing = AnalysisNote.create(ANALYSIS_ID, "Already saved", NOW.minusSeconds(10));
        when(noteRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(existing));

        AnalysisNoteReadModel result = service.put(ANALYSIS_ID, USER_ID, " Already saved ", 0L);

        assertEquals(AnalysisNoteReadModel.from(existing), result);
        verify(noteRepository, never()).update(any());
        verify(noteRepository, never()).delete(any(), anyLong());
        verify(noteRepository, never()).create(any());
        verifyNoAnalysisMutation();
    }

    @Test
    void clearingCurrentNoteDeletesItAndReturnsEmptyProjection() {
        AnalysisNote existing = AnalysisNote.create(ANALYSIS_ID, "Remove this", NOW.minusSeconds(10));
        when(noteRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(existing));

        AnalysisNoteReadModel result = service.put(ANALYSIS_ID, USER_ID, " \t\r\n ", 0L);

        assertEquals(AnalysisNoteReadModel.empty(ANALYSIS_ID), result);
        verify(noteRepository).delete(ANALYSIS_ID, 0L);
        verify(noteRepository, never()).update(any());
        verifyNoAnalysisMutation();
    }

    @Test
    void clearingAbsentNoteWithNullVersionIsSafeNoOp() {
        when(noteRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.empty());

        assertEquals(AnalysisNoteReadModel.empty(ANALYSIS_ID),
                service.put(ANALYSIS_ID, USER_ID, " \n\t ", null));

        verify(noteRepository, never()).create(any());
        verify(noteRepository, never()).delete(any(), anyLong());
        verifyNoAnalysisMutation();
    }

    @Test
    void rejectsNullTextNegativeVersionAndUnsafeControlsBeforePersistence() {
        assertThrows(InvalidAnalysisNoteException.class, () -> service.put(ANALYSIS_ID, USER_ID, null, null));
        assertThrows(InvalidAnalysisNoteException.class, () -> service.put(ANALYSIS_ID, USER_ID, "bad\u0000note", null));
        assertThrows(InvalidAnalysisNoteException.class, () -> service.put(ANALYSIS_ID, USER_ID, "bad\rnote", null));
        assertThrows(InvalidAnalysisNoteException.class, () -> service.put(ANALYSIS_ID, USER_ID, "bad\u0007note", null));
        assertThrows(InvalidAnalysisNoteException.class, () -> service.put(ANALYSIS_ID, USER_ID, "x", -1L));

        verify(noteRepository, never()).findByAnalysisId(any());
        verifyNoAnalysisMutation();
    }

    @Test
    void limitsNormalizedTextByUnicodeCodePoints() {
        String exactlyAtLimit = "😀".repeat(AnalysisNote.MAX_TEXT_CODE_POINTS);
        when(noteRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.empty());
        when(noteRepository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(AnalysisNote.MAX_TEXT_CODE_POINTS,
                service.put(ANALYSIS_ID, USER_ID, exactlyAtLimit, null).text().codePointCount(0, exactlyAtLimit.length()));
        assertThrows(InvalidAnalysisNoteException.class,
                () -> service.put(ANALYSIS_ID, USER_ID, exactlyAtLimit + "😀", null));
    }

    @Test
    void staleOrMissingExpectedVersionConflictsBeforeMutatingNote() {
        AnalysisNote existing = AnalysisNote.create(ANALYSIS_ID, "Current", NOW.minusSeconds(1));
        when(noteRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(existing));

        assertThrows(NoteVersionConflictException.class,
                () -> service.put(ANALYSIS_ID, USER_ID, "Current", null));
        assertThrows(NoteVersionConflictException.class,
                () -> service.put(ANALYSIS_ID, USER_ID, "Replacement", 7L));

        verify(noteRepository, never()).update(any());
        verify(noteRepository, never()).delete(any(), any(Long.class));
        verifyNoAnalysisMutation();
    }

    @Test
    void absentNoteWithUnexpectedVersionConflictsIncludingClear() {
        when(noteRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.empty());

        assertThrows(NoteVersionConflictException.class,
                () -> service.put(ANALYSIS_ID, USER_ID, "New note", 0L));
        assertThrows(NoteVersionConflictException.class,
                () -> service.put(ANALYSIS_ID, USER_ID, "", 0L));

        verify(noteRepository, never()).create(any());
        verifyNoAnalysisMutation();
    }

    @Test
    void foreignOrGuestAnalysisFailsBeforeNoteLookup() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.empty());
        assertThrows(AnalysisNotFoundException.class, () -> service.put(ANALYSIS_ID, USER_ID, "Note", null));

        Analysis guestAnalysis = Analysis.create(ANALYSIS_ID,
                new GuestSessionOwner(UUID.randomUUID(), NOW.plusSeconds(3600)),
                PriceTolerance.exactMatch(), NOW);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(guestAnalysis));
        assertThrows(AnalysisNotFoundException.class, () -> service.put(ANALYSIS_ID, USER_ID, "Note", null));

        verify(noteRepository, never()).findByAnalysisId(any());
        verifyNoAnalysisMutation();
    }

    @Test
    void missingIdentifiersFailBeforeRepositoryAccess() {
        assertThrows(NullPointerException.class, () -> service.put(null, USER_ID, "Note", null));
        assertThrows(NullPointerException.class, () -> service.put(ANALYSIS_ID, null, "Note", null));

        verify(noteRepository, never()).findByAnalysisId(any());
    }

    @Test
    void serviceOwnsTheTransactionBoundary() throws NoSuchMethodException {
        Transactional transaction = PutAnalysisNoteService.class
                .getMethod("put", UUID.class, UUID.class, String.class, Long.class)
                .getAnnotation(Transactional.class);

        assertTrue(transaction != null);
        assertTrue(!transaction.readOnly());
    }

    private void verifyNoAnalysisMutation() {
        verify(analysisRepository, never()).update(any(Analysis.class));
    }
}
