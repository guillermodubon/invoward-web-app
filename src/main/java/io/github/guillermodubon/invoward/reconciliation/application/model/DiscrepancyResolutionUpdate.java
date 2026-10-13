package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;

import java.util.Objects;

/** Validated application command for an optimistic discrepancy-resolution update. */
public record DiscrepancyResolutionUpdate(
        long expectedVersion,
        DiscrepancyResolutionStatus targetStatus,
        DiscrepancyResolutionReason reason,
        String note) {

    public DiscrepancyResolutionUpdate {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");
    }
}
