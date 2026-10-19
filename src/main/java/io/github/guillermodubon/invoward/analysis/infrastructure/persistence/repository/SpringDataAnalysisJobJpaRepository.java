package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJobJpaEntity;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data queries for the single persisted job associated with an Analysis. */
public interface SpringDataAnalysisJobJpaRepository extends JpaRepository<AnalysisJobJpaEntity, UUID> {

    Optional<AnalysisJobJpaEntity> findByAnalysisId(UUID analysisId);

    @Query("select job.analysisId from AnalysisJobJpaEntity job "
            + "where job.status = :status order by job.updatedAt asc, job.analysisId asc")
    List<UUID> findAnalysisIdsByStatus(@Param("status") AnalysisJobStatus status, Pageable pageable);

    @Query("select job.analysisId from AnalysisJobJpaEntity job "
            + "where job.status = :status "
            + "and (:afterAnalysisId is null or job.analysisId > :afterAnalysisId) "
            + "order by job.analysisId asc")
    List<UUID> findAnalysisIdsByStatusAfter(
            @Param("status") AnalysisJobStatus status,
            @Param("afterAnalysisId") UUID afterAnalysisId,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from AnalysisJobJpaEntity job where job.analysisId = :analysisId")
    Optional<AnalysisJobJpaEntity> findByAnalysisIdForUpdate(@Param("analysisId") UUID analysisId);
}
