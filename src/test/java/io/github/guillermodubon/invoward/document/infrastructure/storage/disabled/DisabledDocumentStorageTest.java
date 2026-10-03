package io.github.guillermodubon.invoward.document.infrastructure.storage.disabled;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException.Failure;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisabledDocumentStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyOperationFailsClosedWithSafeUnavailableFailure() {
        DisabledDocumentStorage storage = new DisabledDocumentStorage();
        String sensitiveStorageKey = "users/private-user/analyses/private-analysis/file.pdf";
        StorageObjectUpload upload = new StorageObjectUpload(
                sensitiveStorageKey, temporaryDirectory.resolve("file.pdf"), "application/pdf", 1);

        assertUnavailable(() -> storage.store(upload), sensitiveStorageKey);
        assertUnavailable(() -> storage.downloadTo(sensitiveStorageKey, temporaryDirectory.resolve("out.pdf")),
                sensitiveStorageKey);
        assertUnavailable(() -> storage.delete(sensitiveStorageKey), sensitiveStorageKey);
        assertUnavailable(() -> storage.createPresignedDownload(
                sensitiveStorageKey, "application/pdf", "invoice.pdf", Duration.ofMinutes(5)),
                sensitiveStorageKey);
    }

    private static void assertUnavailable(Runnable operation, String sensitiveValue) {
        DocumentStorageException failure = assertThrows(DocumentStorageException.class, operation::run);
        assertEquals(Failure.UNAVAILABLE, failure.failure());
        assertFalse(failure.getMessage().contains(sensitiveValue));
        assertFalse(failure.toString().contains(sensitiveValue));
    }
}
