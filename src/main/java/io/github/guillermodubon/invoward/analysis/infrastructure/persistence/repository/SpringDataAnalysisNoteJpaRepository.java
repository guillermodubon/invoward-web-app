package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisNoteJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data access for the existing one-note-per-Analysis table. */
public interface SpringDataAnalysisNoteJpaRepository extends JpaRepository<AnalysisNoteJpaEntity, UUID> {

    Optional<AnalysisNoteJpaEntity> findByAnalysisId(UUID analysisId);
}
