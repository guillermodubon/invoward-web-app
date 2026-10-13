package io.github.guillermodubon.invoward.reconciliation.application.port;

import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;

import java.util.List;
import java.util.UUID;

/** Persistence operations for reconciliation snapshots belonging to one Analysis. */
public interface ReconciliationLineRepository {

    List<ReconciliationLine> createAll(List<ReconciliationLine> lines);

    List<ReconciliationLine> findByAnalysisId(UUID analysisId);

    long countByAnalysisId(UUID analysisId);
}
