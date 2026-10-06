package io.github.guillermodubon.invoward.extraction.application.model;

import java.util.Objects;

/** Provider-normalized extraction data safe to reuse across analyses. */
public record CachedExtractionPayload(ExtractionDraft extraction) {

    public CachedExtractionPayload {
        Objects.requireNonNull(extraction, "extraction must not be null");
    }
}
