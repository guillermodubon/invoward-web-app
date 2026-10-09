package io.github.guillermodubon.invoward.reconciliation.api;

/** Stable, safe error contract for the line-item matching API. */
public record LineItemMatchesErrorResponse(String code, String message) {
}
