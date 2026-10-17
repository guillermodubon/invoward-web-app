package io.github.guillermodubon.invoward.reporting.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.reporting.infrastructure.persistence.entity.GeneratedReportJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data queries for the single persisted report associated with an Analysis. */
public interface SpringDataGeneratedReportJpaRepository
        extends JpaRepository<GeneratedReportJpaEntity, UUID> {

    Optional<GeneratedReportJpaEntity> findByAnalysisId(UUID analysisId);

    boolean existsByAnalysisId(UUID analysisId);
}
