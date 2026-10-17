package io.github.guillermodubon.invoward.reporting.application.model;

import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;

import java.util.Objects;

/** Result of persisting one report, including whether this transaction created the winner. */
public record GeneratedReportPersistenceResult(GeneratedReport report, boolean createdByThisCall) {

    public GeneratedReportPersistenceResult {
        Objects.requireNonNull(report, "report must not be null");
    }
}
