package io.github.guillermodubon.invoward.reporting.infrastructure.storage;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException.Failure;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.port.ReportStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentStorageBackedReportStorageTest {

    private static final String STORAGE_KEY = "users/owner/analyses/analysis/reports/random.pdf";
    private static final String FILENAME = "invoward-report-analysis.pdf";
    private static final Duration TTL = Duration.ofMinutes(5);

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesValidatedArtifactThroughProviderNeutralDocumentStorage() throws Exception {
        DocumentStorage documentStorage = mock(DocumentStorage.class);
        DocumentStorageBackedReportStorage storage = new DocumentStorageBackedReportStorage(documentStorage);
        Path pdf = temporaryDirectory.resolve("opaque-temporary-name.pdf");
        byte[] bytes = {0x25, 0x50, 0x44, 0x46, 0x2d};
        Files.write(pdf, bytes);

        try (GeneratedReportArtifact artifact = artifact(pdf, bytes.length)) {
            storage.store(STORAGE_KEY, artifact);

            verify(documentStorage).store(new StorageObjectUpload(
                    STORAGE_KEY, pdf, "application/pdf", bytes.length));
        }
    }

    @Test
    void deletesThroughExistingDocumentStorage() {
        DocumentStorage documentStorage = mock(DocumentStorage.class);
        DocumentStorageBackedReportStorage storage = new DocumentStorageBackedReportStorage(documentStorage);

        storage.delete(STORAGE_KEY);

        verify(documentStorage).delete(STORAGE_KEY);
    }

    @Test
    void translatesStoreFailureToSafeReportStorageFailure(@TempDir Path tempDirectory) throws Exception {
        DocumentStorage documentStorage = mock(DocumentStorage.class);
        DocumentStorageBackedReportStorage storage = new DocumentStorageBackedReportStorage(documentStorage);
        Path pdf = tempDirectory.resolve("report.pdf");
        byte[] bytes = {0x25, 0x50, 0x44, 0x46, 0x2d};
        Files.write(pdf, bytes);
        org.mockito.Mockito.doThrow(new DocumentStorageException(
                        DocumentStorageException.Failure.UNAVAILABLE))
                .when(documentStorage).store(any(StorageObjectUpload.class));

        try (GeneratedReportArtifact artifact = artifact(pdf, bytes.length)) {
            ReportStorageException exception = assertThrows(
                    ReportStorageException.class, () -> storage.store(STORAGE_KEY, artifact));
            assertEquals(Failure.UNAVAILABLE, exception.failure());
            assertFalse(exception.getMessage().contains(STORAGE_KEY));
        }
    }

    @Test
    void translatesDeleteFailureToSafeReportStorageFailure() {
        DocumentStorage documentStorage = mock(DocumentStorage.class);
        DocumentStorageBackedReportStorage storage = new DocumentStorageBackedReportStorage(documentStorage);
        org.mockito.Mockito.doThrow(new DocumentStorageException(
                        DocumentStorageException.Failure.PERMISSION_DENIED))
                .when(documentStorage).delete(STORAGE_KEY);

        ReportStorageException exception = assertThrows(
                ReportStorageException.class, () -> storage.delete(STORAGE_KEY));

        assertEquals(Failure.PERMISSION_DENIED, exception.failure());
        assertFalse(exception.getMessage().contains(STORAGE_KEY));
    }

    @Test
    void presignDelegatesPdfAttachmentAndReturnsRedactedProviderNeutralResult() {
        DocumentStorage documentStorage = mock(DocumentStorage.class);
        DocumentStorageBackedReportStorage storage = new DocumentStorageBackedReportStorage(documentStorage);
        URI signedUrl = URI.create("https://private-storage.invalid/signed/object?signature=secret");
        Instant expiry = Instant.parse("2026-10-14T18:00:00Z");
        when(documentStorage.createPresignedDownload(STORAGE_KEY, "application/pdf", FILENAME, TTL))
                .thenReturn(new PresignedDownload(signedUrl, expiry));

        var download = storage.createPresignedDownload(STORAGE_KEY, FILENAME, TTL);

        assertEquals(signedUrl, download.url());
        assertEquals(expiry, download.expiresAt());
        assertFalse(download.toString().contains(signedUrl.toString()));
        assertFalse(download.toString().contains(STORAGE_KEY));
        verify(documentStorage).createPresignedDownload(STORAGE_KEY, "application/pdf", FILENAME, TTL);
    }

    @Test
    void translatesDocumentStorageFailuresWithoutLeakingKeyOrProviderDetails() throws Exception {
        for (Failure failure : Failure.values()) {
            DocumentStorage documentStorage = mock(DocumentStorage.class);
            DocumentStorageBackedReportStorage storage = new DocumentStorageBackedReportStorage(documentStorage);
            when(documentStorage.createPresignedDownload(STORAGE_KEY, "application/pdf", FILENAME, TTL))
                    .thenThrow(new DocumentStorageException(
                            DocumentStorageException.Failure.valueOf(failure.name())));

            ReportStorageException exception = assertThrows(
                    ReportStorageException.class,
                    () -> storage.createPresignedDownload(STORAGE_KEY, FILENAME, TTL));

            assertEquals(failure, exception.failure());
            assertFalse(exception.getMessage().contains(STORAGE_KEY));
            assertFalse(exception.toString().contains(STORAGE_KEY));
            assertFalse(exception.getMessage().contains("DocumentStorageException"));
        }
    }

    @Test
    void reportPortExposesNoProviderSdkOrDocumentInfrastructureTypes() {
        for (Method method : ReportStorage.class.getDeclaredMethods()) {
            assertFalse(method.getReturnType().getName().startsWith("software.amazon.awssdk"));
            assertTrue(Arrays.stream(method.getParameterTypes())
                    .noneMatch(type -> type.getName().startsWith("software.amazon.awssdk")));
            assertFalse(method.getReturnType().getName().contains("CloudflareR2"));
            assertTrue(Arrays.stream(method.getParameterTypes())
                    .noneMatch(type -> type.getName().contains("CloudflareR2")));
        }
    }

    private static GeneratedReportArtifact artifact(Path path, long sizeBytes) {
        return GeneratedReportArtifact.validated(path, sizeBytes, "a".repeat(64));
    }
}
