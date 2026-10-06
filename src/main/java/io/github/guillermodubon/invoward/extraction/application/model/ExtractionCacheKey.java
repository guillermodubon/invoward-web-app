package io.github.guillermodubon.invoward.extraction.application.model;

import java.util.Objects;

/** Stable identity for provider output, independent of any user or document owner. */
public record ExtractionCacheKey(
        String sha256,
        String extractorVersion,
        String modelId,
        int schemaVersion) {

    public ExtractionCacheKey {
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(extractorVersion, "extractorVersion must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
        if (extractorVersion.isBlank() || extractorVersion.length() > 50) {
            throw new IllegalArgumentException("extractorVersion must contain 1 to 50 characters");
        }
        if (modelId.isBlank() || modelId.length() > 120) {
            throw new IllegalArgumentException("modelId must contain 1 to 120 characters");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
    }
}
