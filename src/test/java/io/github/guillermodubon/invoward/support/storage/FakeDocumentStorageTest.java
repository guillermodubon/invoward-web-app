package io.github.guillermodubon.invoward.support.storage;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException.Failure;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeDocumentStorageTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesInspectsDownloadsAndDeletesExactBytes() throws Exception {
        FakeDocumentStorage storage = new FakeDocumentStorage(fixedClock());
        byte[] content = {0, 1, 2, (byte) 0xff};
        StorageObjectUpload upload = upload(content);

        storage.store(upload);

        assertEquals(1, storage.storedObjectCount());
        assertArrayEquals(content, storage.storedBytes(upload.storageKey()).orElseThrow());
        assertEquals(Optional.of("image/jpeg"), storage.storedContentType(upload.storageKey()));

        Path downloaded = temporaryDirectory.resolve("downloaded.jpg");
        storage.downloadTo(upload.storageKey(), downloaded);
        assertArrayEquals(content, Files.readAllBytes(downloaded));

        storage.delete(upload.storageKey());
        assertEquals(0, storage.storedObjectCount());
        assertTrue(storage.storedBytes(upload.storageKey()).isEmpty());
        assertTrue(storage.storedContentType(upload.storageKey()).isEmpty());
    }

    @Test
    void presignedDownloadIsDeterministicShortLivedAndDoesNotExposeStorageKey() throws Exception {
        FakeDocumentStorage storage = new FakeDocumentStorage(fixedClock());
        StorageObjectUpload upload = upload(new byte[]{1, 2, 3});
        storage.store(upload);
        Duration ttl = Duration.ofMinutes(5);

        PresignedDownload first = storage.createPresignedDownload(
                upload.storageKey(), "image/jpeg", "invoice.jpg", ttl);
        PresignedDownload second = storage.createPresignedDownload(
                upload.storageKey(), "image/jpeg", "invoice.jpg", ttl);

        assertEquals(first, second);
        assertEquals(NOW.plus(ttl), first.expiresAt());
        assertTrue(first.url().isAbsolute());
        assertEquals("fake-storage.invalid", first.url().getHost());
        assertFalse(first.url().toString().contains(upload.storageKey()));
        assertFalse(first.toString().contains(first.url().toString()));
    }

    @Test
    void failuresCanBeInjectedIndependentlyForAllOperations() throws Exception {
        FakeDocumentStorage storage = new FakeDocumentStorage(fixedClock());
        StorageObjectUpload upload = upload(new byte[]{1, 2, 3});
        storage.store(upload);
        Path downloaded = temporaryDirectory.resolve("downloaded.jpg");

        storage.configureFailure(FakeDocumentStorage.Operation.STORE, Failure.UNAVAILABLE);
        assertFailure(Failure.UNAVAILABLE, () -> storage.store(upload));
        storage.clearFailure(FakeDocumentStorage.Operation.STORE);

        storage.configureFailure(FakeDocumentStorage.Operation.DOWNLOAD, Failure.UNKNOWN);
        assertFailure(Failure.UNKNOWN, () -> storage.downloadTo(upload.storageKey(), downloaded));
        storage.clearFailure(FakeDocumentStorage.Operation.DOWNLOAD);

        storage.configureFailure(FakeDocumentStorage.Operation.PRESIGN, Failure.PERMISSION_DENIED);
        assertFailure(Failure.PERMISSION_DENIED, () -> storage.createPresignedDownload(
                upload.storageKey(), "image/jpeg", "invoice.jpg", Duration.ofMinutes(5)));
        storage.clearFailure(FakeDocumentStorage.Operation.PRESIGN);

        storage.configureFailure(FakeDocumentStorage.Operation.DELETE, Failure.UNAVAILABLE);
        assertFailure(Failure.UNAVAILABLE, () -> storage.delete(upload.storageKey()));
        assertEquals(1, storage.storedObjectCount());
    }

    @Test
    void inspectedBytesAreDefensiveCopies() throws Exception {
        FakeDocumentStorage storage = new FakeDocumentStorage(fixedClock());
        byte[] content = {1, 2, 3};
        StorageObjectUpload upload = upload(content);
        storage.store(upload);

        byte[] inspected = storage.storedBytes(upload.storageKey()).orElseThrow();
        inspected[0] = 99;

        assertArrayEquals(content, storage.storedBytes(upload.storageKey()).orElseThrow());
    }

    @Test
    void missingObjectsReportProviderNeutralNotFound() {
        FakeDocumentStorage storage = new FakeDocumentStorage(fixedClock());

        DocumentStorageException failure = assertThrows(
                DocumentStorageException.class,
                () -> storage.downloadTo("unknown-key", temporaryDirectory.resolve("missing.bin")));

        assertEquals(Failure.NOT_FOUND, failure.failure());
        assertNotEquals("unknown-key", failure.getMessage());
    }

    private StorageObjectUpload upload(byte[] content) throws Exception {
        Path source = temporaryDirectory.resolve("source.bin");
        Files.write(source, content);
        return new StorageObjectUpload("users/test/analysis/document.jpg", source, "image/jpeg", content.length);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static void assertFailure(Failure expected, Runnable operation) {
        DocumentStorageException exception = assertThrows(DocumentStorageException.class, operation::run);
        assertEquals(expected, exception.failure());
    }
}
