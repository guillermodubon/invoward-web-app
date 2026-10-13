package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.Numeric19_4;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable output prepared before the short persistence transaction. */
public record ReconciliationPlan(
        UUID analysisId,
        List<ReconciliationLine> lines,
        List<Discrepancy> discrepancies,
        List<DiscrepancyEvidence> evidence,
        BigDecimal analysisDifference) {

    public ReconciliationPlan {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        lines = List.copyOf(lines);
        discrepancies = List.copyOf(discrepancies);
        evidence = List.copyOf(evidence);
        Numeric19_4.requireCompatible(analysisDifference, "analysisDifference");

        Set<UUID> lineIds = new HashSet<>();
        Set<UUID> lineMatchIds = new HashSet<>();
        for (ReconciliationLine line : lines) {
            if (!analysisId.equals(line.analysisId()) || !lineIds.add(line.id())
                    || !lineMatchIds.add(line.lineMatchId())) {
                throw new IllegalArgumentException("plan lines must be unique and belong to its Analysis");
            }
        }

        Set<UUID> discrepancyIds = new HashSet<>();
        Set<UUID> reconciliationLineIds = new HashSet<>();
        lines.forEach(line -> reconciliationLineIds.add(line.id()));
        for (Discrepancy discrepancy : discrepancies) {
            if (!analysisId.equals(discrepancy.analysisId()) || !discrepancyIds.add(discrepancy.id())) {
                throw new IllegalArgumentException("plan discrepancies must be unique and belong to its Analysis");
            }
            if (discrepancy.reconciliationLineId() != null
                    && !reconciliationLineIds.contains(discrepancy.reconciliationLineId())) {
                throw new IllegalArgumentException("line discrepancy must reference a plan line");
            }
            if (discrepancy.resolutionStatus() != DiscrepancyResolutionStatus.OPEN
                    || discrepancy.version() != 0) {
                throw new IllegalArgumentException("new plan discrepancies must start OPEN at version zero");
            }
        }
        Set<UUID> evidenceIds = new HashSet<>();
        for (DiscrepancyEvidence item : evidence) {
            if (!evidenceIds.add(item.id()) || !discrepancyIds.contains(item.discrepancyId())) {
                throw new IllegalArgumentException("plan evidence must reference a plan discrepancy exactly once");
            }
        }
    }

    @Override
    public String toString() {
        return "ReconciliationPlan[analysisId=" + analysisId + ", lineCount=" + lines.size()
                + ", discrepancyCount=" + discrepancies.size() + ", evidenceCount=" + evidence.size() + "]";
    }
}
