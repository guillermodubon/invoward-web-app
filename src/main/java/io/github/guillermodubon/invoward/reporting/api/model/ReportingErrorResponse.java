package io.github.guillermodubon.invoward.reporting.api.model;

/** Stable, provider-neutral error contract for reporting endpoints. */
public record ReportingErrorResponse(String code, String message) {
}
