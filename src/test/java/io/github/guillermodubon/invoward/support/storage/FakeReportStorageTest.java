package io.github.guillermodubon.invoward.support.storage;

import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException.Failure;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeReportStorageTest {

    private static final String STORAGE_KEY = "guest/analysis/reports/random.pdf";
    private static final Instant NOW = Instant.parse("2026-10-14T17:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesCopiesAndDeletesReportBytesAndCreatesOpaqueShortLivedUrl() throws Exception {
        FakeReportStorage storage = new FakeReportStorage(Clock.fixed(NOW, ZoneOffset.UTC));
        byte[] pdf = {0x25, 0x50, 0x44, 0x46, 0x2d};
        Path source = temporaryDirectory.resolve("report.pdf");
        Files.write(source, pdf);

        try (GeneratedReportArtifact artifact = GeneratedReportArtifact.validated(source, pdf.length, "b".repeat(64))) {
            storage.store(STORAGE_KEY, artifact);
        }

        assertEquals(1, storage.storedObjectCount());
        assertArrayEquals(pdf, storage.storedBytes(STORAGE_KEY).orElseThrow());
        var download = storage.createPresignedDownload(
                STORAGE_KEY, "invoward-report-analysis.pdf", Duration.ofMinutes(5));
        assertEquals(NOW.plus(Duration.ofMinutes(5)), download.expiresAt());
        assertFalse(download.url().toString().contains(STORAGE_KEY));
        assertFalse(download.toString().contains(download.url().toString()));

        storage.delete(STORAGE_KEY);

        assertTrue(storage.storedBytes(STORAGE_KEY).isEmpty());
        assertEquals(0, storage.storedObjectCount());
    }

    @Test
    void injectedFailuresAreSafeAndTrackedByOperation() {
        FakeReportStorage storage = new FakeReportStorage(Clock.fixed(NOW, ZoneOffset.UTC));
        storage.configureFailure(FakeReportStorage.Operation.DELETE, Failure.UNAVAILABLE);

        ReportStorageException exception = assertThrows(
                ReportStorageException.class, () -> storage.delete(STORAGE_KEY));

        assertEquals(Failure.UNAVAILABLE, exception.failure());
        assertFalse(exception.getMessage().contains(STORAGE_KEY));
        assertEquals(1, storage.operationCalls(FakeReportStorage.Operation.DELETE));
    }
}
