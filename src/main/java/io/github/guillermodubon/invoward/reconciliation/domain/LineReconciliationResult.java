package io.github.guillermodubon.invoward.reconciliation.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable output of reconciling the confirmed line matches for one Analysis. */
public record LineReconciliationResult(
        UUID analysisId,
        List<ReconciliationLine> lines,
        List<Discrepancy> discrepancies) {

    public LineReconciliationResult {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        lines = List.copyOf(lines);
        discrepancies = List.copyOf(discrepancies);

        Set<UUID> lineIds = new HashSet<>();
        Set<UUID> matchIds = new HashSet<>();
        for (ReconciliationLine line : lines) {
            if (!analysisId.equals(line.analysisId()) || !lineIds.add(line.id()) || !matchIds.add(line.lineMatchId())) {
                throw new IllegalArgumentException("result lines must be unique and belong to its Analysis");
            }
        }

        Set<UUID> discrepancyIds = new HashSet<>();
        for (Discrepancy discrepancy : discrepancies) {
            if (!analysisId.equals(discrepancy.analysisId()) || !discrepancyIds.add(discrepancy.id())) {
                throw new IllegalArgumentException("result discrepancies must be unique and belong to its Analysis");
            }
            if (discrepancy.reconciliationLineId() != null
                    && !lineIds.contains(discrepancy.reconciliationLineId())) {
                throw new IllegalArgumentException("line discrepancy must reference a result line");
            }
        }
    }

    @Override
    public String toString() {
        return "LineReconciliationResult[analysisId=" + analysisId + ", lineCount=" + lines.size()
                + ", discrepancyCount=" + discrepancies.size() + "]";
    }
}
