package io.github.guillermodubon.invoward.reconciliation.application.port;

import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations for a single Analysis line-matching set. */
public interface LineItemMatchRepository {

    LineItemMatch create(LineItemMatch match);

    List<LineItemMatch> createAll(List<LineItemMatch> matches);

    List<LineItemMatch> findByAnalysisId(UUID analysisId);

    List<LineItemMatch> findByAnalysisIdForUpdate(UUID analysisId);

    Optional<LineItemMatch> findByIdAndAnalysisIdForUpdate(UUID matchId, UUID analysisId);

    Optional<LineItemMatch> update(LineItemMatch match);

    boolean deleteByIdAndAnalysisId(UUID matchId, UUID analysisId);

    long countByAnalysisId(UUID analysisId);
}
