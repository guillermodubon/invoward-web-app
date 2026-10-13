package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.DiscrepancyEvidenceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data queries for the evidence rows of an already-authorized discrepancy. */
public interface SpringDataDiscrepancyEvidenceJpaRepository
        extends JpaRepository<DiscrepancyEvidenceJpaEntity, UUID> {

    List<DiscrepancyEvidenceJpaEntity> findByDiscrepancyIdOrderByCreatedAtAscIdAsc(UUID discrepancyId);

    long countByDiscrepancyId(UUID discrepancyId);
}
