package io.github.guillermodubon.invoward.reporting.infrastructure.storage;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException.Failure;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.model.PresignedReportDownload;
import io.github.guillermodubon.invoward.reporting.application.port.ReportStorage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/** Adapts the existing private document storage port for report objects. */
@Component
public final class DocumentStorageBackedReportStorage implements ReportStorage {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final DocumentStorage documentStorage;

    public DocumentStorageBackedReportStorage(DocumentStorage documentStorage) {
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage must not be null");
    }

    @Override
    public void store(String storageKey, GeneratedReportArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact must not be null");
        try {
            documentStorage.store(new StorageObjectUpload(
                    storageKey,
                    artifact.temporaryFile(),
                    PDF_CONTENT_TYPE,
                    artifact.sizeBytes()));
        } catch (DocumentStorageException exception) {
            throw translate(exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            documentStorage.delete(storageKey);
        } catch (DocumentStorageException exception) {
            throw translate(exception);
        }
    }

    @Override
    public PresignedReportDownload createPresignedDownload(
            String storageKey,
            String downloadFilename,
            Duration ttl) {
        try {
            PresignedDownload download = documentStorage.createPresignedDownload(
                    storageKey, PDF_CONTENT_TYPE, downloadFilename, ttl);
            return new PresignedReportDownload(download.url(), download.expiresAt());
        } catch (DocumentStorageException exception) {
            throw translate(exception);
        }
    }

    private static ReportStorageException translate(DocumentStorageException exception) {
        Failure failure = switch (exception.failure()) {
            case NOT_FOUND -> Failure.NOT_FOUND;
            case UNAVAILABLE -> Failure.UNAVAILABLE;
            case PERMISSION_DENIED -> Failure.PERMISSION_DENIED;
            case INVALID_CONFIGURATION -> Failure.INVALID_CONFIGURATION;
            case UNKNOWN -> Failure.UNKNOWN;
        };
        return new ReportStorageException(failure);
    }
}
