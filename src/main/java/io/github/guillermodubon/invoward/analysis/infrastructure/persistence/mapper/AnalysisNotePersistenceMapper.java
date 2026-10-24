package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisNote;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisNoteJpaEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Maps private Analysis note data without exposing its persistence entity to application code. */
@Component
public class AnalysisNotePersistenceMapper {

    public AnalysisNoteJpaEntity toNewEntity(AnalysisNote note) {
        Objects.requireNonNull(note, "note must not be null");
        AnalysisNoteJpaEntity entity = AnalysisNoteJpaEntity.create();
        entity.setAnalysisId(note.analysisId());
        entity.setNoteText(note.text());
        entity.setCreatedAt(note.createdAt());
        entity.setUpdatedAt(note.updatedAt());
        return entity;
    }

    public void updateEntity(AnalysisNote note, AnalysisNoteJpaEntity entity) {
        Objects.requireNonNull(note, "note must not be null");
        Objects.requireNonNull(entity, "entity must not be null");
        if (!note.analysisId().equals(entity.getAnalysisId())) {
            throw new IllegalArgumentException("Analysis note id must match persistence entity analysis id");
        }
        if (!note.createdAt().equals(entity.getCreatedAt())) {
            throw new IllegalArgumentException("Analysis note createdAt cannot be changed");
        }
        entity.setNoteText(note.text());
        entity.setUpdatedAt(note.updatedAt());
    }

    public AnalysisNote toDomain(AnalysisNoteJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new AnalysisNote(
                entity.getAnalysisId(),
                entity.getNoteText(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
