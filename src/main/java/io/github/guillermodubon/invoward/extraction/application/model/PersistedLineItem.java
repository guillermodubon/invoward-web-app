package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;

import java.util.Objects;
import java.util.UUID;

/** A line item together with its database-generated identity, without exposing persistence types. */
public record PersistedLineItem(UUID id, ExtractedLineItem line) {

    public PersistedLineItem {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(line, "line must not be null");
    }

    @Override
    public String toString() {
        return "PersistedLineItem[idPresent=true, position=" + line.position() + "]";
    }
}
