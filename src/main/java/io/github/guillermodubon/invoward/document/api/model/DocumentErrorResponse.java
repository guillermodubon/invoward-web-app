package io.github.guillermodubon.invoward.document.api.model;

/** Stable, safe error contract for document upload requests. */
public record DocumentErrorResponse(String code, String message) {
}
