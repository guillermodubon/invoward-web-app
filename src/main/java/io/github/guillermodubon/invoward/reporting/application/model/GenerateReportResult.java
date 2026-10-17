package io.github.guillermodubon.invoward.reporting.application.model;

import java.util.Objects;

/** The safe download response and whether this call created the immutable report. */
public record GenerateReportResult(ReportDownload report, boolean createdByThisCall) {

    public GenerateReportResult {
        Objects.requireNonNull(report, "report must not be null");
    }
}
