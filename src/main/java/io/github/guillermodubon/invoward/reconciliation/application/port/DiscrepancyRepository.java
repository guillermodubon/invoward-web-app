package io.github.guillermodubon.invoward.reconciliation.application.port;

import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations for Analysis-scoped discrepancies and their resolution lifecycle. */
public interface DiscrepancyRepository {

    List<Discrepancy> createAll(List<Discrepancy> discrepancies);

    List<Discrepancy> findByAnalysisId(UUID analysisId);

    long countByAnalysisId(UUID analysisId);

    Optional<Discrepancy> findByIdAndAnalysisId(UUID discrepancyId, UUID analysisId);

    Optional<Discrepancy> findByIdAndAnalysisIdForUpdate(UUID discrepancyId, UUID analysisId);

    Optional<Discrepancy> update(Discrepancy discrepancy);
}
