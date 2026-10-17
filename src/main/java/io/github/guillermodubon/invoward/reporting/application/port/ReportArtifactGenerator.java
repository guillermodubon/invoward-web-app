package io.github.guillermodubon.invoward.reporting.application.port;

import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;

/** Produces a validated, bounded, temporary PDF artifact from a report snapshot. */
public interface ReportArtifactGenerator {

    GeneratedReportArtifact generate(ReportSnapshot snapshot);
}
