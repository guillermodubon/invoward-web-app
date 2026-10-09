package io.github.guillermodubon.invoward.reconciliation.application.model;

import java.util.Objects;
import java.util.UUID;

/** Validated application command for one optimistic, user-reviewed match update. */
public record ManualLineItemMatchUpdate(
        long expectedVersion,
        ManualMatchAction action,
        UUID referenceLineItemId,
        UUID invoiceLineItemId) {

    public ManualLineItemMatchUpdate {
        Objects.requireNonNull(action, "action must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (action == ManualMatchAction.MATCH_WITH) {
            Objects.requireNonNull(referenceLineItemId, "referenceLineItemId is required for MATCH_WITH");
            Objects.requireNonNull(invoiceLineItemId, "invoiceLineItemId is required for MATCH_WITH");
        } else if (referenceLineItemId != null || invoiceLineItemId != null) {
            throw new IllegalArgumentException("line identities are only accepted for MATCH_WITH");
        }
    }
}
