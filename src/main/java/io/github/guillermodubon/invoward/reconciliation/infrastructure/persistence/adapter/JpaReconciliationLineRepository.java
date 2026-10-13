package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.reconciliation.application.port.ReconciliationLineRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.ReconciliationLineJpaEntity;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.mapper.ReconciliationLinePersistenceMapper;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository.SpringDataReconciliationLineJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-backed adapter for immutable reconciliation snapshots. */
@Repository
@Transactional
public class JpaReconciliationLineRepository implements ReconciliationLineRepository {

    private final SpringDataReconciliationLineJpaRepository repository;
    private final ReconciliationLinePersistenceMapper mapper;

    public JpaReconciliationLineRepository(
            SpringDataReconciliationLineJpaRepository repository,
            ReconciliationLinePersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public List<ReconciliationLine> createAll(List<ReconciliationLine> lines) {
        Objects.requireNonNull(lines, "lines must not be null");
        List<ReconciliationLineJpaEntity> entities = List.copyOf(lines).stream()
                .map(mapper::toEntity)
                .toList();
        if (entities.isEmpty()) {
            return List.of();
        }
        return repository.saveAllAndFlush(entities).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationLine> findByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.findByAnalysisIdOrderByCreatedAtAscIdAsc(analysisId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.countByAnalysisId(analysisId);
    }
}
