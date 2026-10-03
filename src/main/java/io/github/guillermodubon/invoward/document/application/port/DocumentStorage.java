package io.github.guillermodubon.invoward.document.application.port;

import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;

import java.nio.file.Path;
import java.time.Duration;

/** Provider-neutral operations for private document object storage. */
public interface DocumentStorage {

    /** Stores a fully validated file at its server-generated key. */
    void store(StorageObjectUpload upload);

    /** Streams an object to the supplied destination file. */
    void downloadTo(String storageKey, Path destination);

    /** Deletes an object, primarily to compensate for a failed database transaction. */
    void delete(String storageKey);

    /** Creates a short-lived download URL for an authorized object. */
    PresignedDownload createPresignedDownload(
            String storageKey,
            String contentType,
            String downloadFilename,
            Duration ttl);
}
