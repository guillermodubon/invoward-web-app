package io.github.guillermodubon.invoward.analysis.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisNoteTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-24T14:00:00Z");

    @Test
    void newNoteStartsAtVersionZeroAndUsesCreationTimeForBothTimestamps() {
        AnalysisNote note = AnalysisNote.create(UUID.randomUUID(), "Private note", CREATED_AT);

        assertEquals(0, note.version());
        assertEquals(CREATED_AT, note.createdAt());
        assertEquals(CREATED_AT, note.updatedAt());
    }

    @Test
    void textReplacementPreservesIdentityCreationTimeAndPersistenceVersion() {
        AnalysisNote original = AnalysisNote.create(UUID.randomUUID(), "Before", CREATED_AT);
        Instant changedAt = CREATED_AT.plusSeconds(1);

        AnalysisNote changed = original.replaceText("After", changedAt);

        assertEquals(original.analysisId(), changed.analysisId());
        assertEquals("After", changed.text());
        assertEquals(0, changed.version());
        assertEquals(CREATED_AT, changed.createdAt());
        assertEquals(changedAt, changed.updatedAt());
    }

    @Test
    void unchangedTextIsAnIdentityNoOp() {
        AnalysisNote original = AnalysisNote.create(UUID.randomUUID(), "Same", CREATED_AT);

        assertSame(original, original.replaceText("Same", CREATED_AT.plusSeconds(10)));
    }

    @Test
    void rejectsBlankOversizedNegativeVersionAndNonMonotonicTimestamps() {
        UUID analysisId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisNote.create(analysisId, " \t ", CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisNote.create(analysisId, "😀".repeat(AnalysisNote.MAX_TEXT_CODE_POINTS + 1), CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisNote(analysisId, "Valid", -1, CREATED_AT, CREATED_AT));

        AnalysisNote note = AnalysisNote.create(analysisId, "Valid", CREATED_AT);
        assertThrows(IllegalArgumentException.class,
                () -> note.replaceText("Changed", CREATED_AT.minusNanos(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisNote(analysisId, "Valid", 0, CREATED_AT, CREATED_AT.minusNanos(1)));
    }
}
