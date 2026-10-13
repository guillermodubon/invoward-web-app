package io.github.guillermodubon.invoward.reconciliation.application.port;

import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;

import java.util.List;
import java.util.UUID;

/** Read and bulk-persist source evidence associated with a discrepancy. */
public interface DiscrepancyEvidenceRepository {

    List<DiscrepancyEvidence> createAll(List<DiscrepancyEvidence> evidence);

    List<DiscrepancyEvidence> findByDiscrepancyId(UUID discrepancyId);

    long countByDiscrepancyId(UUID discrepancyId);
}
