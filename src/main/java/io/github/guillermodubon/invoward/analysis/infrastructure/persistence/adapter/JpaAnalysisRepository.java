package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJpaEntity;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.mapper.AnalysisPersistenceMapper;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository.SpringDataAnalysisJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed Analysis repository adapter. */
@Repository
@Transactional
public class JpaAnalysisRepository implements AnalysisRepository {

    private final SpringDataAnalysisJpaRepository repository;
    private final AnalysisPersistenceMapper mapper;

    public JpaAnalysisRepository(
            SpringDataAnalysisJpaRepository repository,
            AnalysisPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Analysis create(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(analysis)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Analysis> findOwnedById(UUID analysisId, AnalysisOwner owner, Instant now) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Optional<AnalysisJpaEntity> entity = findOwnedEntity(analysisId, owner, now);
        return entity.map(mapper::toDomain);
    }

    @Override
    public Optional<Analysis> findOwnedByIdForUpdate(UUID analysisId, AnalysisOwner owner, Instant now) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Optional<AnalysisJpaEntity> entity = findOwnedEntityForUpdate(analysisId, owner, now);
        return entity.map(mapper::toDomain);
    }

    @Override
    public Analysis update(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        AnalysisJpaEntity entity = repository.findById(analysis.id())
                .orElseThrow(() -> new EntityNotFoundException("Analysis does not exist"));
        if (entity.getVersion() != analysis.version()) {
            throw new OptimisticLockException("Analysis was modified concurrently");
        }
        mapper.updateEntity(analysis, entity);
        repository.flush();
        return mapper.toDomain(entity);
    }

    private Optional<AnalysisJpaEntity> findOwnedEntity(
            UUID analysisId, AnalysisOwner owner, Instant now) {
        if (owner instanceof RegisteredUserOwner registeredOwner) {
            return repository.findByIdAndUserId(analysisId, registeredOwner.userId());
        }
        if (owner instanceof GuestSessionOwner guestOwner) {
            return repository.findByIdAndGuestSessionIdAndExpiresAtAfter(
                    analysisId, guestOwner.guestSessionId(), now);
        }
        throw new IllegalArgumentException("Unsupported Analysis owner type");
    }

    private Optional<AnalysisJpaEntity> findOwnedEntityForUpdate(
            UUID analysisId, AnalysisOwner owner, Instant now) {
        if (owner instanceof RegisteredUserOwner registeredOwner) {
            return repository.findOwnedByIdForUpdateAndUserId(analysisId, registeredOwner.userId());
        }
        if (owner instanceof GuestSessionOwner guestOwner) {
            return repository.findOwnedByIdForUpdateAndGuestSessionId(
                    analysisId, guestOwner.guestSessionId(), now);
        }
        throw new IllegalArgumentException("Unsupported Analysis owner type");
    }
}
