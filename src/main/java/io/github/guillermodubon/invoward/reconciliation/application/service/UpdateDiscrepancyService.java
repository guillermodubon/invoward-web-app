package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.reconciliation.application.model.DiscrepancyResolutionUpdate;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Application entry point for owner-scoped discrepancy resolution. */
@Service
public class UpdateDiscrepancyService {

    private final UpdateDiscrepancyTransaction transaction;

    public UpdateDiscrepancyService(UpdateDiscrepancyTransaction transaction) {
        this.transaction = Objects.requireNonNull(transaction);
    }

    public Discrepancy update(
            UUID analysisId,
            AnalysisOwner owner,
            UUID discrepancyId,
            DiscrepancyResolutionUpdate update) {
        return transaction.update(analysisId, owner, discrepancyId, update);
    }
}
