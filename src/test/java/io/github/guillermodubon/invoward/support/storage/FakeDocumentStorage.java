package io.github.guillermodubon.invoward.support.storage;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException.Failure;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory storage test double; it is never registered in production. */
public final class FakeDocumentStorage implements DocumentStorage {

    private static final String FAKE_URL_BASE = "https://fake-storage.invalid/download/";

    private final Map<String, StoredObject> objects = new java.util.HashMap<>();
    private final Map<Operation, Failure> failures = new EnumMap<>(Operation.class);
    private final Map<Operation, Integer> operationCalls = new EnumMap<>(Operation.class);
    private final Clock clock;
    private int downloadCalls;

    public FakeDocumentStorage() {
        this(Clock.systemUTC());
    }

    public FakeDocumentStorage(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public synchronized void store(StorageObjectUpload upload) {
        Objects.requireNonNull(upload, "upload must not be null");
        recordCall(Operation.STORE);
        failIfConfigured(Operation.STORE);
        try {
            byte[] bytes = Files.readAllBytes(upload.sourceFile());
            if (bytes.length != upload.sizeBytes()) {
                throw new DocumentStorageException(Failure.UNKNOWN);
            }
            objects.put(upload.storageKey(), new StoredObject(bytes, upload.contentType()));
        } catch (IOException exception) {
            throw new DocumentStorageException(Failure.UNKNOWN);
        }
    }

    @Override
    public synchronized void downloadTo(String storageKey, Path destination) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        downloadCalls++;
        recordCall(Operation.DOWNLOAD);
        failIfConfigured(Operation.DOWNLOAD);
        StoredObject storedObject = objects.get(storageKey);
        if (storedObject == null) {
            throw new DocumentStorageException(Failure.NOT_FOUND);
        }
        try {
            Files.write(destination, storedObject.bytes());
        } catch (IOException exception) {
            throw new DocumentStorageException(Failure.UNKNOWN);
        }
    }

    @Override
    public synchronized void delete(String storageKey) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        recordCall(Operation.DELETE);
        failIfConfigured(Operation.DELETE);
        objects.remove(storageKey);
    }

    @Override
    public synchronized PresignedDownload createPresignedDownload(
            String storageKey,
            String contentType,
            String downloadFilename,
            Duration ttl) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        requireNonBlank(contentType, "contentType");
        requireNonBlank(downloadFilename, "downloadFilename");
        Objects.requireNonNull(ttl, "ttl must not be null");
        recordCall(Operation.PRESIGN);
        failIfConfigured(Operation.PRESIGN);
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be greater than 0");
        }

        Instant expiresAt = clock.instant().plus(ttl);
        UUID opaqueObjectId = UUID.nameUUIDFromBytes(storageKey.getBytes(StandardCharsets.UTF_8));
        URI url = URI.create(FAKE_URL_BASE + opaqueObjectId + "?expires=" + expiresAt.toEpochMilli());
        return new PresignedDownload(url, expiresAt);
    }

    public synchronized int storedObjectCount() {
        return objects.size();
    }

    public synchronized int downloadCalls() {
        return downloadCalls;
    }

    public synchronized int operationCalls(Operation operation) {
        return operationCalls.getOrDefault(Objects.requireNonNull(operation), 0);
    }

    public synchronized Optional<byte[]> storedBytes(String storageKey) {
        StoredObject storedObject = objects.get(storageKey);
        return storedObject == null
                ? Optional.empty()
                : Optional.of(storedObject.bytes());
    }

    public synchronized Optional<String> storedContentType(String storageKey) {
        StoredObject storedObject = objects.get(storageKey);
        return storedObject == null
                ? Optional.empty()
                : Optional.of(storedObject.contentType());
    }

    /** Makes the selected operation fail until its failure is cleared. */
    public synchronized void configureFailure(Operation operation, Failure failure) {
        failures.put(
                Objects.requireNonNull(operation, "operation must not be null"),
                Objects.requireNonNull(failure, "failure must not be null"));
    }

    public synchronized void clearFailure(Operation operation) {
        failures.remove(Objects.requireNonNull(operation, "operation must not be null"));
    }

    public synchronized void clear() {
        objects.clear();
        failures.clear();
        operationCalls.clear();
        downloadCalls = 0;
    }

    private void recordCall(Operation operation) {
        operationCalls.merge(operation, 1, Integer::sum);
    }

    private void failIfConfigured(Operation operation) {
        Failure failure = failures.get(operation);
        if (failure != null) {
            throw new DocumentStorageException(failure);
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    public enum Operation {
        STORE,
        DOWNLOAD,
        DELETE,
        PRESIGN
    }

    private record StoredObject(byte[] bytes, String contentType) {

        private StoredObject {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
