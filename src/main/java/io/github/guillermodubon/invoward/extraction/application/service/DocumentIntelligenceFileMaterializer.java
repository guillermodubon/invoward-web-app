package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentMaterializationException;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/** Downloads private source bytes to an opaque temporary file and always removes that file. */
@Service
public class DocumentIntelligenceFileMaterializer {

    private final DocumentStorage documentStorage;

    public DocumentIntelligenceFileMaterializer(DocumentStorage documentStorage) {
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage must not be null");
    }

    public <T> T withMaterializedFile(Document document, Function<DocumentIntelligenceInput, T> operation) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Path temporaryFile;
        try {
            temporaryFile = Files.createTempFile("invoward-ai-", extensionFor(document.contentType()));
        } catch (IOException exception) {
            throw new DocumentMaterializationException();
        }

        RuntimeException primaryFailure = null;
        try {
            documentStorage.downloadTo(document.storageKey(), temporaryFile);
            DocumentIntelligenceInput input = new DocumentIntelligenceInput(
                    document.id(), document.role(), document.contentType(), document.pageCount(), temporaryFile);
            return operation.apply(input);
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupFailure) {
                DocumentMaterializationException safeFailure = new DocumentMaterializationException();
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(safeFailure);
                } else {
                    throw safeFailure;
                }
            }
        }
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> throw new DocumentStorageException(DocumentStorageException.Failure.UNKNOWN);
        };
    }
}
