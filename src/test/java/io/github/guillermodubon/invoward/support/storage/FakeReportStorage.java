package io.github.guillermodubon.invoward.support.storage;

import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException.Failure;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.model.PresignedReportDownload;
import io.github.guillermodubon.invoward.reporting.application.port.ReportStorage;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory report storage test double; it is never registered in production. */
public final class FakeReportStorage implements ReportStorage {

    private static final String FAKE_URL_BASE = "https://fake-report-storage.invalid/download/";

    private final Map<String, byte[]> objects = new java.util.HashMap<>();
    private final Map<Operation, Failure> failures = new EnumMap<>(Operation.class);
    private final Map<Operation, Integer> operationCalls = new EnumMap<>(Operation.class);
    private final Clock clock;

    public FakeReportStorage() {
        this(Clock.systemUTC());
    }

    public FakeReportStorage(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public synchronized void store(String storageKey, GeneratedReportArtifact artifact) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(artifact, "artifact must not be null");
        recordCall(Operation.STORE);
        failIfConfigured(Operation.STORE);
        try {
            byte[] bytes = Files.readAllBytes(artifact.temporaryFile());
            if (bytes.length != artifact.sizeBytes()) {
                throw new ReportStorageException(Failure.UNKNOWN);
            }
            objects.put(storageKey, bytes.clone());
        } catch (IOException exception) {
            throw new ReportStorageException(Failure.UNKNOWN);
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
    public synchronized PresignedReportDownload createPresignedDownload(
            String storageKey,
            String downloadFilename,
            Duration ttl) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
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
        return new PresignedReportDownload(url, expiresAt);
    }

    public synchronized int storedObjectCount() {
        return objects.size();
    }

    public synchronized int operationCalls(Operation operation) {
        return operationCalls.getOrDefault(Objects.requireNonNull(operation), 0);
    }

    public synchronized Optional<byte[]> storedBytes(String storageKey) {
        byte[] bytes = objects.get(storageKey);
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }

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
    }

    private void recordCall(Operation operation) {
        operationCalls.merge(operation, 1, Integer::sum);
    }

    private void failIfConfigured(Operation operation) {
        Failure failure = failures.get(operation);
        if (failure != null) {
            throw new ReportStorageException(failure);
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    public enum Operation {
        STORE,
        DELETE,
        PRESIGN
    }
}
