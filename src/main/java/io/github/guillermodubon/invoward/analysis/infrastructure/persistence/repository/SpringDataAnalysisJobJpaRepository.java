package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJobJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Spring Data queries for the single persisted job associated with an Analysis. */
public interface SpringDataAnalysisJobJpaRepository extends JpaRepository<AnalysisJobJpaEntity, UUID> {

    Optional<AnalysisJobJpaEntity> findByAnalysisId(UUID analysisId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from AnalysisJobJpaEntity job where job.analysisId = :analysisId")
    Optional<AnalysisJobJpaEntity> findByAnalysisIdForUpdate(@Param("analysisId") UUID analysisId);
}
