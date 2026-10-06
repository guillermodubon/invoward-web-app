package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class DocumentIntelligenceFileMaterializerTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void downloadsExactPrivateBytesAndDeletesTemporaryFileAfterSuccessfulOperation() throws Exception {
        DocumentStorage storage = mock(DocumentStorage.class);
        byte[] expected = new byte[]{1, 2, 3, 4};
        AtomicReference<Path> downloadedPath = new AtomicReference<>();
        doAnswer(invocation -> {
            Path path = invocation.getArgument(1);
            downloadedPath.set(path);
            Files.write(path, expected);
            return null;
        }).when(storage).downloadTo(anyString(), any(Path.class));
        Document document = document();

        byte[] actual = new DocumentIntelligenceFileMaterializer(storage)
                .withMaterializedFile(document, input -> {
                    assertFalse(input.temporaryFile().getFileName().toString().contains(document.storageKey()));
                    try {
                        return Files.readAllBytes(input.temporaryFile());
                    } catch (IOException exception) {
                        throw new AssertionError(exception);
                    }
                });

        assertArrayEquals(expected, actual);
        assertFalse(Files.exists(downloadedPath.get()));
        verify(storage).downloadTo(org.mockito.ArgumentMatchers.eq(document.storageKey()), any(Path.class));
        verifyNoMoreInteractions(storage);
    }

    @Test
    void deletesTemporaryFileWhenStorageDownloadFails() {
        DocumentStorage storage = mock(DocumentStorage.class);
        AtomicReference<Path> downloadedPath = new AtomicReference<>();
        doAnswer(invocation -> {
            downloadedPath.set(invocation.getArgument(1));
            throw new DocumentStorageException(DocumentStorageException.Failure.UNAVAILABLE);
        }).when(storage).downloadTo(anyString(), any(Path.class));

        assertThrows(DocumentStorageException.class,
                () -> new DocumentIntelligenceFileMaterializer(storage)
                        .withMaterializedFile(document(), input -> null));

        assertFalse(Files.exists(downloadedPath.get()));
        verify(storage).downloadTo(anyString(), any(Path.class));
        verifyNoMoreInteractions(storage);
    }

    @Test
    void deletesTemporaryFileWhenProviderOperationFailsAndNeverUsesPresigning() {
        DocumentStorage storage = mock(DocumentStorage.class);
        AtomicReference<Path> downloadedPath = new AtomicReference<>();
        doAnswer(invocation -> {
            Path path = invocation.getArgument(1);
            downloadedPath.set(path);
            Files.write(path, new byte[]{9});
            return null;
        }).when(storage).downloadTo(anyString(), any(Path.class));

        assertThrows(DocumentIntelligenceException.class,
                () -> new DocumentIntelligenceFileMaterializer(storage)
                        .withMaterializedFile(document(), input -> {
                            throw new DocumentIntelligenceException(
                                    DocumentIntelligenceException.Failure.UNAVAILABLE);
                        }));

        assertFalse(Files.exists(downloadedPath.get()));
        verify(storage).downloadTo(anyString(), any(Path.class));
        verifyNoMoreInteractions(storage);
    }

    private static Document document() {
        Analysis analysis = Analysis.create(UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()),
                PriceTolerance.exactMatch(), NOW);
        return Document.createUploaded(UUID.randomUUID(), analysis.id(), DocumentRole.REFERENCE,
                "private-original.pdf", "application/pdf", 4, 1, "a".repeat(64),
                "private-storage-key", null, NOW);
    }
}
