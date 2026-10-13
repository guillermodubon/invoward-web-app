package io.github.guillermodubon.invoward.reconciliation.api.model;

import io.github.guillermodubon.invoward.reconciliation.application.model.DiscrepancyResolutionUpdate;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** HTTP request for an optimistic discrepancy resolution transition. */
public record UpdateDiscrepancyRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull DiscrepancyResolutionStatus resolutionStatus,
        DiscrepancyResolutionReason resolutionReason,
        String resolutionNote) {

    public DiscrepancyResolutionUpdate toCommand() {
        return new DiscrepancyResolutionUpdate(
                expectedVersion,
                resolutionStatus,
                resolutionReason,
                resolutionNote);
    }
}
