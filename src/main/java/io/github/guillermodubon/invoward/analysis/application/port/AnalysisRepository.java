package io.github.guillermodubon.invoward.analysis.application.port;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations required by Analysis use cases. */
public interface AnalysisRepository {

    Analysis create(Analysis analysis);

    Optional<Analysis> findOwnedById(UUID analysisId, AnalysisOwner owner, Instant now);

    Optional<Analysis> findOwnedByIdForUpdate(UUID analysisId, AnalysisOwner owner, Instant now);

    Analysis update(Analysis analysis);
}
