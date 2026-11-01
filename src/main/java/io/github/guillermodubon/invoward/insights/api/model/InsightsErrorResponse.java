package io.github.guillermodubon.invoward.insights.api.model;

/** Stable, non-sensitive error response for dashboard endpoints. */
public record InsightsErrorResponse(String code, String message) {
}
