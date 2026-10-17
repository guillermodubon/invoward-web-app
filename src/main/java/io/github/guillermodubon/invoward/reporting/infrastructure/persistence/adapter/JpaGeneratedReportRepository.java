package io.github.guillermodubon.invoward.reporting.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.reporting.infrastructure.persistence.mapper.GeneratedReportPersistenceMapper;
import io.github.guillermodubon.invoward.reporting.infrastructure.persistence.repository.SpringDataGeneratedReportJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed adapter for generated-report metadata. */
@Repository
@Transactional
public class JpaGeneratedReportRepository implements GeneratedReportRepository {

    private final SpringDataGeneratedReportJpaRepository repository;
    private final GeneratedReportPersistenceMapper mapper;

    public JpaGeneratedReportRepository(
            SpringDataGeneratedReportJpaRepository repository,
            GeneratedReportPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public GeneratedReport create(GeneratedReport report) {
        Objects.requireNonNull(report, "report must not be null");
        return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(report)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GeneratedReport> findByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.findByAnalysisId(analysisId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.existsByAnalysisId(analysisId);
    }
}
