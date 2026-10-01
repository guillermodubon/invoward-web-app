package io.github.guillermodubon.invoward.analysis.api.model;

/** Stable, safe error contract for the Analysis API. */
public record AnalysisErrorResponse(String code, String message) {
}
