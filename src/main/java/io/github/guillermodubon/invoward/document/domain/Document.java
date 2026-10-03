package io.github.guillermodubon.invoward.document.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Persistable metadata for one original input document. */
public record Document(
        UUID id,
        UUID analysisId,
        DocumentRole role,
        DocumentType detectedType,
        DocumentType confirmedType,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Integer pageCount,
        String sha256,
        String storageKey,
        Instant expiresAt,
        Instant createdAt) {

    private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public Document {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        requireNonBlank(originalFilename, "originalFilename");
        requireNonBlank(contentType, "contentType");
        requireNonBlank(storageKey, "storageKey");
        Objects.requireNonNull(pageCount, "pageCount must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (originalFilename.codePointCount(0, originalFilename.length()) > 255) {
            throw new IllegalArgumentException("originalFilename must not exceed 255 code points");
        }
        if (contentType.length() > 100) {
            throw new IllegalArgumentException("contentType must not exceed 100 characters");
        }
        if (storageKey.length() > 512) {
            throw new IllegalArgumentException("storageKey must not exceed 512 characters");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be greater than 0");
        }
        if (pageCount <= 0) {
            throw new IllegalArgumentException("pageCount must be greater than 0");
        }
        if (sha256 == null || !SHA_256_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
        if (confirmedType != null && !isConfirmedTypeAllowed(role, confirmedType)) {
            throw new IllegalArgumentException("confirmedType is not compatible with role");
        }
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    /** Creates the unclassified metadata state used when an original document is uploaded. */
    public static Document createUploaded(
            UUID id,
            UUID analysisId,
            DocumentRole role,
            String originalFilename,
            String contentType,
            long sizeBytes,
            Integer pageCount,
            String sha256,
            String storageKey,
            Instant expiresAt,
            Instant createdAt) {
        return new Document(id, analysisId, role, null, null, originalFilename, contentType,
                sizeBytes, pageCount, sha256, storageKey, expiresAt, createdAt);
    }

    private static boolean isConfirmedTypeAllowed(DocumentRole role, DocumentType type) {
        return switch (role) {
            case REFERENCE -> type == DocumentType.QUOTE
                    || type == DocumentType.ESTIMATE
                    || type == DocumentType.PURCHASE_ORDER
                    || type == DocumentType.UNKNOWN;
            case INVOICE -> type == DocumentType.INVOICE || type == DocumentType.UNKNOWN;
        };
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    @Override
    public String toString() {
        return "Document[id=" + id + ", analysisId=" + analysisId + ", role=" + role
                + ", sizeBytes=" + sizeBytes + ", pageCount=" + pageCount + "]";
    }
}
