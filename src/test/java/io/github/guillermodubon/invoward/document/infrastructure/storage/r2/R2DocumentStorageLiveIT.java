package io.github.guillermodubon.invoward.document.infrastructure.storage.r2;

import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.infrastructure.config.CloudflareR2Configuration;
import io.github.guillermodubon.invoward.document.infrastructure.config.DocumentStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit, opt-in contract test against the configured private R2 bucket. */
class R2DocumentStorageLiveIT {

    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(2);

    @Test
    @EnabledIfEnvironmentVariable(named = "R2_LIVE_TEST", matches = "true")
    void realAdapterCanStoreDownloadPresignAndDeleteOneSyntheticObject() throws IOException {
        String accountId = requiredEnvironmentVariable("R2_ACCOUNT_ID");
        String bucket = requiredEnvironmentVariable("R2_BUCKET");
        String accessKeyId = requiredEnvironmentVariable("R2_ACCESS_KEY_ID");
        String secretAccessKey = requiredEnvironmentVariable("R2_SECRET_ACCESS_KEY");

        new ApplicationContextRunner()
                .withUserConfiguration(R2LiveConfiguration.class)
                .withPropertyValues(
                        "invoward.document-storage.provider=r2",
                        "invoward.document-storage.r2.account-id=" + accountId,
                        "invoward.document-storage.r2.bucket=" + bucket,
                        "invoward.document-storage.r2.access-key-id=" + accessKeyId,
                        "invoward.document-storage.r2.secret-access-key=" + secretAccessKey)
                .run(context -> {
                    assertTrue(context.isRunning());
                    DocumentStorage storage = context.getBean(DocumentStorage.class);
                    verifyLiveRoundTrip(storage);
                });
    }

    private static void verifyLiveRoundTrip(DocumentStorage storage) {
        String storageKey = "live-tests/" + UUID.randomUUID() + "/synthetic-object.bin";
        byte[] syntheticContent = "InvoWard synthetic R2 adapter integration check"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path directory;
        try {
            directory = Files.createTempDirectory("invoward-r2-live-");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create temporary R2 live-test files");
        }

        Path source = directory.resolve("synthetic-source.bin");
        Path downloaded = directory.resolve("synthetic-download.bin");
        try {
            Files.write(source, syntheticContent);
            storage.store(new StorageObjectUpload(
                    storageKey, source, "application/octet-stream", syntheticContent.length));
            storage.downloadTo(storageKey, downloaded);
            assertArrayEquals(syntheticContent, Files.readAllBytes(downloaded));

            Instant beforePresign = Instant.now();
            PresignedDownload signedDownload = storage.createPresignedDownload(
                    storageKey,
                    "application/octet-stream",
                    "synthetic-object.bin",
                    DOWNLOAD_TTL);
            assertTrue(signedDownload.url().isAbsolute());
            assertTrue(signedDownload.expiresAt().isAfter(beforePresign));
            assertFalse(signedDownload.expiresAt().isAfter(beforePresign.plus(DOWNLOAD_TTL)));
        } catch (IOException exception) {
            throw new IllegalStateException("R2 live-test file operation failed");
        } finally {
            try {
                storage.delete(storageKey);
            } finally {
                deleteTemporaryFiles(source, downloaded, directory);
            }
        }
    }

    private static void deleteTemporaryFiles(Path source, Path downloaded, Path directory) {
        try {
            Files.deleteIfExists(source);
            Files.deleteIfExists(downloaded);
            Files.deleteIfExists(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not remove temporary R2 live-test files");
        }
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("R2 live test requires " + name);
        }
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentStorageProperties.class)
    @Import(CloudflareR2Configuration.class)
    static class R2LiveConfiguration {
    }
}
