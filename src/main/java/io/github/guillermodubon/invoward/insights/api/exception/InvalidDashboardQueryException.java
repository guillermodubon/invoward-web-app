package io.github.guillermodubon.invoward.insights.api.exception;

/** Safe transport-level error for malformed dashboard filters and pagination. */
public final class InvalidDashboardQueryException extends RuntimeException {

    public InvalidDashboardQueryException() {
        super("Dashboard query is invalid.");
    }
}
