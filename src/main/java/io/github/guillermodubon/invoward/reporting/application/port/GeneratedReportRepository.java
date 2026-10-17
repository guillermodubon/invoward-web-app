package io.github.guillermodubon.invoward.reporting.application.port;

import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;

import java.util.Optional;
import java.util.UUID;

/** Persistence operations required by reporting use cases. */
public interface GeneratedReportRepository {

    GeneratedReport create(GeneratedReport report);

    Optional<GeneratedReport> findByAnalysisId(UUID analysisId);

    boolean existsByAnalysisId(UUID analysisId);
}
