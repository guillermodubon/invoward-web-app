package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.mapper.AnalysisJobPersistenceMapper;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository.SpringDataAnalysisJobJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed AnalysisJob repository adapter. */
@Repository
@Transactional
public class JpaAnalysisJobRepository implements AnalysisJobRepository {

    private final SpringDataAnalysisJobJpaRepository repository;
    private final AnalysisJobPersistenceMapper mapper;

    public JpaAnalysisJobRepository(
            SpringDataAnalysisJobJpaRepository repository,
            AnalysisJobPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public AnalysisJob create(AnalysisJob analysisJob) {
        Objects.requireNonNull(analysisJob, "analysisJob must not be null");
        return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(analysisJob)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnalysisJob> findByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.findByAnalysisId(analysisId).map(mapper::toDomain);
    }

    @Override
    public Optional<AnalysisJob> findJobByAnalysisIdForUpdate(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.findByAnalysisIdForUpdate(analysisId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findAnalysisIdsByStatus(AnalysisJobStatus status, int limit) {
        Objects.requireNonNull(status, "status must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return List.copyOf(repository.findAnalysisIdsByStatus(status, PageRequest.of(0, limit)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findAnalysisIdsByStatusAfter(
            AnalysisJobStatus status,
            UUID afterAnalysisId,
            int limit) {
        Objects.requireNonNull(status, "status must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return List.copyOf(repository.findAnalysisIdsByStatusAfter(
                status, afterAnalysisId, PageRequest.of(0, limit)));
    }

    @Override
    public AnalysisJob update(AnalysisJob analysisJob) {
        Objects.requireNonNull(analysisJob, "analysisJob must not be null");
        var entity = repository.findById(analysisJob.id())
                .orElseThrow(() -> new EntityNotFoundException("Analysis job does not exist"));
        mapper.updateEntity(analysisJob, entity);
        repository.flush();
        return mapper.toDomain(entity);
    }
}
