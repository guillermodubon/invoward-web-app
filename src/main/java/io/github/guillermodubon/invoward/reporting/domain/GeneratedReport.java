package io.github.guillermodubon.invoward.reporting.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable metadata for one privately stored PDF report. */
public record GeneratedReport(
        UUID id,
        UUID analysisId,
        String storageKey,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant expiresAt,
        Instant generatedAt) {

    private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final int MAX_STORAGE_KEY_LENGTH = 512;

    public GeneratedReport {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");

        if (storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        if (storageKey.length() > MAX_STORAGE_KEY_LENGTH) {
            throw new IllegalArgumentException("storageKey must not exceed 512 characters");
        }
        if (!PDF_CONTENT_TYPE.equals(contentType)) {
            throw new IllegalArgumentException("contentType must be application/pdf");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be greater than 0");
        }
        if (!SHA_256_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
    }

    @Override
    public String toString() {
        return "GeneratedReport[id=" + id + ", analysisId=" + analysisId
                + ", contentType=" + contentType + ", sizeBytes=" + sizeBytes
                + ", generatedAt=" + generatedAt + "]";
    }
}
