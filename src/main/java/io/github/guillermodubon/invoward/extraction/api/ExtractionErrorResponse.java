package io.github.guillermodubon.invoward.extraction.api;

/** Stable, safe error contract for document classification and extraction APIs. */
public record ExtractionErrorResponse(String code, String message) {
}
