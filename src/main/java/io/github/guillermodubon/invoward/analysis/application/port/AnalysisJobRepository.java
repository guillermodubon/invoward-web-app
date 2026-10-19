package io.github.guillermodubon.invoward.analysis.application.port;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations required by Analysis job use cases. */
public interface AnalysisJobRepository {

    AnalysisJob create(AnalysisJob analysisJob);

    Optional<AnalysisJob> findByAnalysisId(UUID analysisId);

    Optional<AnalysisJob> findJobByAnalysisIdForUpdate(UUID analysisId);

    /** Returns a bounded oldest-first scan of persisted jobs in the requested status. */
    List<UUID> findAnalysisIdsByStatus(AnalysisJobStatus status, int limit);

    /** Returns a bounded, stable-ID recovery page after the supplied cursor (null for the first page). */
    List<UUID> findAnalysisIdsByStatusAfter(
            AnalysisJobStatus status,
            UUID afterAnalysisId,
            int limit);

    AnalysisJob update(AnalysisJob analysisJob);
}
