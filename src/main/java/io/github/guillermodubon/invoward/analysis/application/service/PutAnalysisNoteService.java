package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.exception.InvalidAnalysisNoteException;
import io.github.guillermodubon.invoward.analysis.application.exception.NoteVersionConflictException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisNoteReadModel;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisNoteRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisNote;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates, replaces, or clears a registered user's private Analysis note atomically. */
@Service
public class PutAnalysisNoteService {

    private final AnalysisRepository analysisRepository;
    private final AnalysisNoteRepository noteRepository;
    private final Clock clock;

    public PutAnalysisNoteService(
            AnalysisRepository analysisRepository,
            AnalysisNoteRepository noteRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository, "analysisRepository must not be null");
        this.noteRepository = Objects.requireNonNull(noteRepository, "noteRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public AnalysisNoteReadModel put(
            UUID analysisId,
            UUID registeredUserId,
            String text,
            Long expectedVersion) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        RegisteredUserOwner owner = new RegisteredUserOwner(
                Objects.requireNonNull(registeredUserId, "registeredUserId must not be null"));
        String normalizedText = normalizeAndValidate(text);
        validateExpectedVersion(expectedVersion);

        // PostgreSQL TIMESTAMPTZ stores microseconds; keep write responses identical to subsequent reads.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .filter(owned -> owner.equals(owned.owner()))
                .orElseThrow(AnalysisNotFoundException::new);

        Optional<AnalysisNote> existingNote = noteRepository.findByAnalysisId(analysis.id());
        if (existingNote.isEmpty()) {
            if (expectedVersion != null) {
                throw new NoteVersionConflictException();
            }
            if (normalizedText.isEmpty()) {
                return AnalysisNoteReadModel.empty(analysis.id());
            }
            return AnalysisNoteReadModel.from(noteRepository.create(
                    AnalysisNote.create(analysis.id(), normalizedText, now)));
        }

        AnalysisNote current = existingNote.orElseThrow();
        requireExpectedVersion(current, expectedVersion);
        if (normalizedText.isEmpty()) {
            noteRepository.delete(analysis.id(), current.version());
            return AnalysisNoteReadModel.empty(analysis.id());
        }
        if (current.text().equals(normalizedText)) {
            return AnalysisNoteReadModel.from(current);
        }
        return AnalysisNoteReadModel.from(noteRepository.update(current.replaceText(normalizedText, now)));
    }

    private static String normalizeAndValidate(String text) {
        if (text == null) {
            throw new InvalidAnalysisNoteException();
        }

        String normalized = text.replace("\r\n", "\n");
        validateCharacters(normalized);
        normalized = normalized.strip();
        if (normalized.codePointCount(0, normalized.length()) > AnalysisNote.MAX_TEXT_CODE_POINTS) {
            throw new InvalidAnalysisNoteException();
        }
        return normalized;
    }

    private static void validateCharacters(String text) {
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            if ((codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)
                    || (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t')) {
                throw new InvalidAnalysisNoteException();
            }
            index += Character.charCount(codePoint);
        }
    }

    private static void validateExpectedVersion(Long expectedVersion) {
        if (expectedVersion != null && expectedVersion < 0) {
            throw new InvalidAnalysisNoteException();
        }
    }

    private static void requireExpectedVersion(AnalysisNote current, Long expectedVersion) {
        if (expectedVersion == null || current.version() != expectedVersion) {
            throw new NoteVersionConflictException();
        }
    }
}
