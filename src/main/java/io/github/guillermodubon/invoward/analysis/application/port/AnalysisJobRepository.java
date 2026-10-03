package io.github.guillermodubon.invoward.analysis.application.port;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;

import java.util.Optional;
import java.util.UUID;

/** Persistence operations required by Analysis job use cases. */
public interface AnalysisJobRepository {

    AnalysisJob create(AnalysisJob analysisJob);

    Optional<AnalysisJob> findByAnalysisId(UUID analysisId);

    Optional<AnalysisJob> findJobByAnalysisIdForUpdate(UUID analysisId);

    AnalysisJob update(AnalysisJob analysisJob);
}
