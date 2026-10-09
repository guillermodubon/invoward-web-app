package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.LineItemMatchJpaEntity;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.mapper.LineItemMatchPersistenceMapper;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository.SpringDataLineItemMatchJpaRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed adapter for persisted line-item matches. */
@Repository
@Transactional
public class JpaLineItemMatchRepository implements LineItemMatchRepository {

    private final SpringDataLineItemMatchJpaRepository repository;
    private final LineItemMatchPersistenceMapper mapper;

    public JpaLineItemMatchRepository(
            SpringDataLineItemMatchJpaRepository repository,
            LineItemMatchPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public LineItemMatch create(LineItemMatch match) {
        Objects.requireNonNull(match, "match must not be null");
        return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(match)));
    }

    @Override
    public List<LineItemMatch> createAll(List<LineItemMatch> matches) {
        Objects.requireNonNull(matches, "matches must not be null");
        List<LineItemMatchJpaEntity> entities = List.copyOf(matches).stream()
                .map(mapper::toEntity)
                .toList();
        if (entities.isEmpty()) {
            return List.of();
        }
        return repository.saveAllAndFlush(entities).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineItemMatch> findByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.findByAnalysisIdOrderByCreatedAtAscIdAsc(analysisId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<LineItemMatch> findByAnalysisIdForUpdate(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.findByAnalysisIdForUpdate(analysisId).stream().map(mapper::toDomain).toList();
    }

    @Override
    public Optional<LineItemMatch> findByIdAndAnalysisIdForUpdate(UUID matchId, UUID analysisId) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.findByIdAndAnalysisIdForUpdate(matchId, analysisId).map(mapper::toDomain);
    }

    @Override
    public Optional<LineItemMatch> update(LineItemMatch match) {
        Objects.requireNonNull(match, "match must not be null");
        return repository.findByIdAndAnalysisId(match.id(), match.analysisId()).map(entity -> {
            if (entity.getVersion() != match.version()) {
                throw new ObjectOptimisticLockingFailureException(LineItemMatchJpaEntity.class, match.id());
            }
            mapper.updateEntity(match, entity);
            repository.flush();
            return mapper.toDomain(entity);
        });
    }

    @Override
    public boolean deleteByIdAndAnalysisId(UUID matchId, UUID analysisId) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.deleteByIdAndAnalysisId(matchId, analysisId) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public long countByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.countByAnalysisId(analysisId);
    }
}
