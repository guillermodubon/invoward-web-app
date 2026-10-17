package io.github.guillermodubon.invoward.reporting.application.port;

import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;

import java.nio.file.Path;

/** Renders a report snapshot to a caller-provided destination. */
public interface ReportGenerator {

    void generate(ReportSnapshot snapshot, Path destination);
}
