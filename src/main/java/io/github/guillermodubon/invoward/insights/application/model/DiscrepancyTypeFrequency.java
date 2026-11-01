package io.github.guillermodubon.invoward.insights.application.model;

import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;

import java.util.Objects;

/** Persisted discrepancy-row frequency for one existing reconciliation type. */
public record DiscrepancyTypeFrequency(DiscrepancyType type, long count) {

    public DiscrepancyTypeFrequency {
        Objects.requireNonNull(type, "type is required");
        if (count < 0) {
            throw new IllegalArgumentException("Discrepancy count cannot be negative.");
        }
    }
}
