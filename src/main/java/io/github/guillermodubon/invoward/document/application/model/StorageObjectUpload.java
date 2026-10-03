package io.github.guillermodubon.invoward.document.application.model;

import java.nio.file.Path;
import java.util.Objects;

/** A previously validated, file-backed object ready for storage. */
public record StorageObjectUpload(
        String storageKey,
        Path sourceFile,
        String contentType,
        long sizeBytes) {

    public StorageObjectUpload {
        requireNonBlank(storageKey, "storageKey");
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");
        requireNonBlank(contentType, "contentType");
        if (storageKey.length() > 512) {
            throw new IllegalArgumentException("storageKey must not exceed 512 characters");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be greater than 0");
        }
    }

    @Override
    public String toString() {
        return "StorageObjectUpload[contentType=" + contentType + ", sizeBytes=" + sizeBytes + "]";
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
