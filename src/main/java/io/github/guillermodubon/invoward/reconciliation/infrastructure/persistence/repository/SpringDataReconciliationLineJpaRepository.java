package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.ReconciliationLineJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data queries scoped to persisted reconciliation snapshots. */
public interface SpringDataReconciliationLineJpaRepository
        extends JpaRepository<ReconciliationLineJpaEntity, UUID> {

    List<ReconciliationLineJpaEntity> findByAnalysisIdOrderByCreatedAtAscIdAsc(UUID analysisId);

    long countByAnalysisId(UUID analysisId);
}
