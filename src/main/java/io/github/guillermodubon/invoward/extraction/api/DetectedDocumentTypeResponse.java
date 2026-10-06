package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.DetectedDocumentType;

import java.util.Objects;
import java.util.UUID;

/** Public, provider-neutral result of detecting one uploaded document's type. */
public record DetectedDocumentTypeResponse(UUID documentId, DocumentRole role, DocumentType detectedType) {

    public DetectedDocumentTypeResponse {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(detectedType, "detectedType must not be null");
    }

    public static DetectedDocumentTypeResponse from(DetectedDocumentType detectedDocumentType) {
        Objects.requireNonNull(detectedDocumentType, "detectedDocumentType must not be null");
        return new DetectedDocumentTypeResponse(
                detectedDocumentType.documentId(),
                detectedDocumentType.role(),
                detectedDocumentType.detectedType());
    }
}
