package io.github.guillermodubon.invoward.document.infrastructure.storage.disabled;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException.Failure;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;

import java.nio.file.Path;
import java.time.Duration;

/** Safe local/default provider that fails closed until a storage backend is configured. */
public final class DisabledDocumentStorage implements DocumentStorage {

    @Override
    public void store(StorageObjectUpload upload) {
        throw unavailable();
    }

    @Override
    public void downloadTo(String storageKey, Path destination) {
        throw unavailable();
    }

    @Override
    public void delete(String storageKey) {
        throw unavailable();
    }

    @Override
    public PresignedDownload createPresignedDownload(
            String storageKey,
            String contentType,
            String downloadFilename,
            Duration ttl) {
        throw unavailable();
    }

    private static DocumentStorageException unavailable() {
        return new DocumentStorageException(Failure.UNAVAILABLE);
    }
}
