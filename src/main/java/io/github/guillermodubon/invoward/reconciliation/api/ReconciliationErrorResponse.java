package io.github.guillermodubon.invoward.reconciliation.api;

/** Stable, non-sensitive error contract for reconciliation endpoints. */
public record ReconciliationErrorResponse(String code, String message) {
}
