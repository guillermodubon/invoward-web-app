package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJobJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data queries for the single persisted job associated with an Analysis. */
public interface SpringDataAnalysisJobJpaRepository extends JpaRepository<AnalysisJobJpaEntity, UUID> {

    Optional<AnalysisJobJpaEntity> findByAnalysisId(UUID analysisId);
}
