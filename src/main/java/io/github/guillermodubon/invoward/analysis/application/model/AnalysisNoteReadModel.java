package io.github.guillermodubon.invoward.analysis.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisNote;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe application projection for reading the single private note of an owned Analysis. */
public record AnalysisNoteReadModel(
        UUID analysisId,
        String text,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public AnalysisNoteReadModel {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        if (text == null) {
            if (version != null || createdAt != null || updatedAt != null) {
                throw new IllegalArgumentException("An empty note must not have persistence metadata");
            }
        } else {
            if (text.isBlank() || version == null || version < 0 || createdAt == null || updatedAt == null
                    || updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("Analysis note read model is inconsistent");
            }
        }
    }

    public static AnalysisNoteReadModel empty(UUID analysisId) {
        return new AnalysisNoteReadModel(analysisId, null, null, null, null);
    }

    public static AnalysisNoteReadModel from(AnalysisNote note) {
        Objects.requireNonNull(note, "note must not be null");
        return new AnalysisNoteReadModel(
                note.analysisId(), note.text(), note.version(), note.createdAt(), note.updatedAt());
    }
}
