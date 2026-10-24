package io.github.guillermodubon.invoward.analysis.api.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisNoteReadModel;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable HTTP representation of a registered user's private Analysis note. */
public record AnalysisNoteResponse(
        UUID analysisId,
        String text,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public AnalysisNoteResponse {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
    }

    public static AnalysisNoteResponse from(AnalysisNoteReadModel note) {
        Objects.requireNonNull(note, "note must not be null");
        return new AnalysisNoteResponse(
                note.analysisId(), note.text(), note.version(), note.createdAt(), note.updatedAt());
    }
}
