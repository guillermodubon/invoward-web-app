package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;

import java.util.Objects;
import java.util.UUID;

/** Persisted classification result for one upload slot. */
public record DetectedDocumentType(UUID documentId, DocumentRole role, DocumentType detectedType) {

    public DetectedDocumentType {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(detectedType, "detectedType must not be null");
    }
}
