package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;

import java.util.Objects;
import java.util.UUID;

/** Creates private, opaque object keys for generated report snapshots. */
public final class ReportStorageKeyGenerator {

    public String generate(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");

        String prefix = switch (analysis.owner()) {
            case RegisteredUserOwner registered ->
                    "users/" + registered.userId() + "/analyses/" + analysis.id() + "/reports/";
            case GuestSessionOwner ignored -> "guest/" + analysis.id() + "/reports/";
        };
        return prefix + UUID.randomUUID() + ".pdf";
    }
}
