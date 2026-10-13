package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyEvidenceRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.DiscrepancyEvidenceJpaEntity;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.mapper.DiscrepancyEvidencePersistenceMapper;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository.SpringDataDiscrepancyEvidenceJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-backed adapter for confirmed-source discrepancy evidence. */
@Repository
@Transactional
public class JpaDiscrepancyEvidenceRepository implements DiscrepancyEvidenceRepository {

    private final SpringDataDiscrepancyEvidenceJpaRepository repository;
    private final DiscrepancyEvidencePersistenceMapper mapper;

    public JpaDiscrepancyEvidenceRepository(
            SpringDataDiscrepancyEvidenceJpaRepository repository,
            DiscrepancyEvidencePersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public List<DiscrepancyEvidence> createAll(List<DiscrepancyEvidence> evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        List<DiscrepancyEvidenceJpaEntity> entities = List.copyOf(evidence).stream()
                .map(mapper::toEntity)
                .toList();
        if (entities.isEmpty()) {
            return List.of();
        }
        return repository.saveAllAndFlush(entities).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscrepancyEvidence> findByDiscrepancyId(UUID discrepancyId) {
        Objects.requireNonNull(discrepancyId, "discrepancyId must not be null");
        return repository.findByDiscrepancyIdOrderByCreatedAtAscIdAsc(discrepancyId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByDiscrepancyId(UUID discrepancyId) {
        Objects.requireNonNull(discrepancyId, "discrepancyId must not be null");
        return repository.countByDiscrepancyId(discrepancyId);
    }
}
