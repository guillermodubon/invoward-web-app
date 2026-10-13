package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyConflictException;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.DiscrepancyJpaEntity;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.mapper.DiscrepancyPersistenceMapper;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository.SpringDataDiscrepancyJpaRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed adapter for analysis-scoped discrepancy snapshots and resolution state. */
@Repository
@Transactional
public class JpaDiscrepancyRepository implements DiscrepancyRepository {

    private final SpringDataDiscrepancyJpaRepository repository;
    private final DiscrepancyPersistenceMapper mapper;

    public JpaDiscrepancyRepository(
            SpringDataDiscrepancyJpaRepository repository,
            DiscrepancyPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public List<Discrepancy> createAll(List<Discrepancy> discrepancies) {
        Objects.requireNonNull(discrepancies, "discrepancies must not be null");
        List<DiscrepancyJpaEntity> entities = List.copyOf(discrepancies).stream()
                .map(mapper::toEntity)
                .toList();
        if (entities.isEmpty()) {
            return List.of();
        }
        return repository.saveAllAndFlush(entities).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Discrepancy> findByAnalysisId(UUID analysisId) {
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

    @Override
    @Transactional(readOnly = true)
    public Optional<Discrepancy> findByIdAndAnalysisId(UUID discrepancyId, UUID analysisId) {
        requireIds(discrepancyId, analysisId);
        return repository.findByIdAndAnalysisId(discrepancyId, analysisId).map(mapper::toDomain);
    }

    @Override
    public Optional<Discrepancy> findByIdAndAnalysisIdForUpdate(UUID discrepancyId, UUID analysisId) {
        requireIds(discrepancyId, analysisId);
        return repository.findByIdAndAnalysisIdForUpdate(discrepancyId, analysisId).map(mapper::toDomain);
    }

    @Override
    public Optional<Discrepancy> update(Discrepancy discrepancy) {
        Objects.requireNonNull(discrepancy, "discrepancy must not be null");
        return repository.findByIdAndAnalysisId(discrepancy.id(), discrepancy.analysisId()).map(entity -> {
            if (entity.getVersion() != discrepancy.version()) {
                throw new DiscrepancyConflictException();
            }
            try {
                mapper.updateResolution(discrepancy, entity);
                repository.flush();
                return mapper.toDomain(entity);
            } catch (OptimisticLockingFailureException concurrentUpdate) {
                throw new DiscrepancyConflictException();
            }
        });
    }

    private static void requireIds(UUID discrepancyId, UUID analysisId) {
        Objects.requireNonNull(discrepancyId, "discrepancyId must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
    }
}
