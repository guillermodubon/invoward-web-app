package io.github.guillermodubon.invoward.document.application.model;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentStorageModelTest {

    @Test
    void storageUploadRequiresAKeyFileMimeTypeAndPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> new StorageObjectUpload(
                " ", Path.of("staged.pdf"), "application/pdf", 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageObjectUpload(
                "server/generated-key", Path.of("staged.pdf"), "application/pdf", 0));
        assertThrows(IllegalArgumentException.class, () -> new StorageObjectUpload(
                "server/generated-key", Path.of("staged.pdf"), " ", 1));
    }

    @Test
    void storageUploadStringRepresentationDoesNotExposeKeyOrLocalPath() {
        StorageObjectUpload upload = new StorageObjectUpload(
                "private-user/private-analysis/object.pdf", Path.of("private-local-path/staged.pdf"),
                "application/pdf", 20);

        assertFalse(upload.toString().contains("private-user"));
        assertFalse(upload.toString().contains("private-local-path"));
    }

    @Test
    void presignedDownloadRequiresHttpsHostAndExpiration() {
        assertThrows(IllegalArgumentException.class,
                () -> new PresignedDownload(URI.create("http://storage.example/download"), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new PresignedDownload(URI.create("/relative/download"), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new PresignedDownload(URI.create("https://user:pass@storage.example/download"), Instant.now()));
    }

    @Test
    void presignedDownloadStringRepresentationRedactsSignedUrl() {
        URI signedUrl = URI.create("https://storage.example/download?signature=private-signature");
        PresignedDownload download = new PresignedDownload(signedUrl, Instant.now());

        assertFalse(download.toString().contains("private-signature"));
        assertTrue(download.toString().contains("<redacted>"));
    }
}
