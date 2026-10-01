package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.mapper.AnalysisJobPersistenceMapper;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository.SpringDataAnalysisJobJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
}
