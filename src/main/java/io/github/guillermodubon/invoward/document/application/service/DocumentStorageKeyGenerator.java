package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.model.DocumentFileFormat;

import java.util.Objects;
import java.util.UUID;

/** Creates opaque, server-generated object keys scoped to the Analysis owner. */
public final class DocumentStorageKeyGenerator {

    public String generate(AnalysisOwner owner, UUID analysisId, DocumentFileFormat format) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(format, "format must not be null");

        String prefix = switch (owner) {
            case RegisteredUserOwner registered ->
                    "users/" + registered.userId() + "/analyses/" + analysisId + "/documents/";
            case GuestSessionOwner ignored -> "guest/" + analysisId + "/documents/";
        };
        return prefix + UUID.randomUUID() + format.canonicalExtension();
    }
}
