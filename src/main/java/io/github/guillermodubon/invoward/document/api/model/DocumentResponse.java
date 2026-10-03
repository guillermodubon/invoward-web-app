package io.github.guillermodubon.invoward.document.api.model;

import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe public metadata projection for an uploaded document. */
public record DocumentResponse(
        UUID id,
        DocumentRole role,
        DocumentType detectedType,
        DocumentType confirmedType,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Integer pageCount,
        Instant expiresAt,
        Instant createdAt) {

    public static DocumentResponse from(Document document) {
        Objects.requireNonNull(document, "document must not be null");
        return new DocumentResponse(
                document.id(),
                document.role(),
                document.detectedType(),
                document.confirmedType(),
                document.originalFilename(),
                document.contentType(),
                document.sizeBytes(),
                document.pageCount(),
                document.expiresAt(),
                document.createdAt());
    }
}
