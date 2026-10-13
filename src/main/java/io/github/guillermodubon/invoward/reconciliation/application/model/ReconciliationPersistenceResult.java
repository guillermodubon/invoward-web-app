package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;

import java.util.List;
import java.util.Objects;

/** Persisted snapshots and workflow state returned by the atomic reconciliation transaction. */
public record ReconciliationPersistenceResult(
        boolean createdByThisCall,
        Analysis analysis,
        List<ReconciliationLine> lines,
        List<Discrepancy> discrepancies) {

    public ReconciliationPersistenceResult {
        Objects.requireNonNull(analysis, "analysis must not be null");
        lines = List.copyOf(lines);
        discrepancies = List.copyOf(discrepancies);
        if (lines.stream().anyMatch(line -> !analysis.id().equals(line.analysisId()))
                || discrepancies.stream().anyMatch(item -> !analysis.id().equals(item.analysisId()))) {
            throw new IllegalArgumentException("persisted rows must belong to the result Analysis");
        }
    }
}
