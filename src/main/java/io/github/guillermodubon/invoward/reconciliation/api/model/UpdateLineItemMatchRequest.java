package io.github.guillermodubon.invoward.reconciliation.api.model;

import io.github.guillermodubon.invoward.reconciliation.application.model.ManualLineItemMatchUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualMatchAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/** HTTP request for one optimistic manual match correction. */
public record UpdateLineItemMatchRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull ManualMatchAction action,
        UUID referenceLineItemId,
        UUID invoiceLineItemId) {

    public ManualLineItemMatchUpdate toCommand() {
        return new ManualLineItemMatchUpdate(
                expectedVersion,
                action,
                referenceLineItemId,
                invoiceLineItemId);
    }
}
