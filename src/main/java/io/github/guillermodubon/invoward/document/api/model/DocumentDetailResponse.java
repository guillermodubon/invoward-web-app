package io.github.guillermodubon.invoward.document.api.model;

import io.github.guillermodubon.invoward.document.application.model.DocumentDownload;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe document metadata with its short-lived download address. */
public record DocumentDetailResponse(
        UUID id,
        DocumentRole role,
        DocumentType detectedType,
        DocumentType confirmedType,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Integer pageCount,
        Instant expiresAt,
        Instant createdAt,
        String downloadUrl,
        Instant downloadUrlExpiresAt) {

    public static DocumentDetailResponse from(DocumentDownload download) {
        Objects.requireNonNull(download, "download must not be null");
        Document document = download.document();
        return new DocumentDetailResponse(
                document.id(),
                document.role(),
                document.detectedType(),
                document.confirmedType(),
                document.originalFilename(),
                document.contentType(),
                document.sizeBytes(),
                document.pageCount(),
                document.expiresAt(),
                document.createdAt(),
                download.download().url().toString(),
                download.download().expiresAt());
    }
}
