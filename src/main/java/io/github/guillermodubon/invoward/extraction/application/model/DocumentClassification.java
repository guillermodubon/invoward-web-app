package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.document.domain.DocumentType;

import java.util.Objects;

/** Provider-neutral classification suggestion; it is not a user-confirmed document type. */
public record DocumentClassification(DocumentType detectedType) {

    public DocumentClassification {
        Objects.requireNonNull(detectedType, "detectedType must not be null");
    }
}
