package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.DiscrepancyJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data queries scoped to one Analysis, including locked resolution updates. */
public interface SpringDataDiscrepancyJpaRepository extends JpaRepository<DiscrepancyJpaEntity, UUID> {

    List<DiscrepancyJpaEntity> findByAnalysisIdOrderByCreatedAtAscIdAsc(UUID analysisId);

    long countByAnalysisId(UUID analysisId);

    Optional<DiscrepancyJpaEntity> findByIdAndAnalysisId(UUID id, UUID analysisId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select discrepancy from DiscrepancyJpaEntity discrepancy "
            + "where discrepancy.id = :discrepancyId and discrepancy.analysisId = :analysisId")
    Optional<DiscrepancyJpaEntity> findByIdAndAnalysisIdForUpdate(
            @Param("discrepancyId") UUID discrepancyId,
            @Param("analysisId") UUID analysisId);
}
