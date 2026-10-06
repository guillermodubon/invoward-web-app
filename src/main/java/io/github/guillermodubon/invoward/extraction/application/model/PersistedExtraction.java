package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Extraction aggregate plus persistence metadata needed for review concurrency. */
public record PersistedExtraction(
        UUID id,
        UUID documentId,
        ExtractedDocument extraction,
        List<UUID> lineItemIds,
        String extractorVersion,
        String modelId,
        int schemaVersion,
        long version,
        Instant extractedAt,
        Instant createdAt,
        Instant updatedAt) {

    public PersistedExtraction {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(extraction, "extraction must not be null");
        lineItemIds = List.copyOf(lineItemIds);
        if (lineItemIds.size() != extraction.lines().size()
                || new HashSet<>(lineItemIds).size() != lineItemIds.size()) {
            throw new IllegalArgumentException("line item identities must match the persisted extraction lines");
        }
        Objects.requireNonNull(extractorVersion, "extractorVersion must not be null");
        Objects.requireNonNull(extractedAt, "extractedAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (extractorVersion.isBlank() || extractorVersion.length() > 50) {
            throw new IllegalArgumentException("extractorVersion must contain 1 to 50 characters");
        }
        if (modelId != null && (modelId.isBlank() || modelId.length() > 120)) {
            throw new IllegalArgumentException("modelId must contain 1 to 120 characters when present");
        }
        if (schemaVersion < 1 || version < 0) {
            throw new IllegalArgumentException("schemaVersion must be positive and version nonnegative");
        }
    }

    public PersistedExtraction withExtraction(ExtractedDocument updatedExtraction) {
        return withExtraction(updatedExtraction, lineItemIds);
    }

    public PersistedExtraction withExtraction(ExtractedDocument updatedExtraction, List<UUID> updatedLineItemIds) {
        return new PersistedExtraction(id, documentId, updatedExtraction, updatedLineItemIds, extractorVersion, modelId,
                schemaVersion, version, extractedAt, createdAt, updatedAt);
    }
}
