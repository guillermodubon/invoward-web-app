package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Private, file-backed input shared by provider adapters without exposing storage infrastructure. */
public record DocumentIntelligenceInput(
        UUID documentId,
        DocumentRole role,
        String contentType,
        Integer pageCount,
        Path temporaryFile) {

    public DocumentIntelligenceInput {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(pageCount, "pageCount must not be null");
        Objects.requireNonNull(temporaryFile, "temporaryFile must not be null");
        if (pageCount < 1) {
            throw new IllegalArgumentException("pageCount must be positive");
        }
        if (!"application/pdf".equals(contentType)
                && !"image/jpeg".equals(contentType)
                && !"image/png".equals(contentType)) {
            throw new IllegalArgumentException("contentType is not supported for document intelligence");
        }
    }

    @Override
    public String toString() {
        return "DocumentIntelligenceInput[role=" + role + ", contentType=" + contentType
                + ", pageCount=" + pageCount + "]";
    }
}
