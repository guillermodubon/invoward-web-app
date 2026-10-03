package io.github.guillermodubon.invoward.document.infrastructure.config;

import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.infrastructure.storage.disabled.DisabledDocumentStorage;
import io.github.guillermodubon.invoward.document.infrastructure.storage.r2.CloudflareR2DocumentStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentConfigurationPropertiesTest {

    private static final String ACCOUNT_ID = "fake-account-id";
    private static final String BUCKET = "fake-private-bucket";
    private static final String ACCESS_KEY = "fake-access-key";
    private static final String SECRET_KEY = "fake-secret-key";
    private static final String[] COMPLETE_R2_PROPERTIES = {
            "invoward.document-storage.provider=r2",
            "invoward.document-storage.r2.account-id=" + ACCOUNT_ID,
            "invoward.document-storage.r2.bucket=" + BUCKET,
            "invoward.document-storage.r2.access-key-id=" + ACCESS_KEY,
            "invoward.document-storage.r2.secret-access-key=" + SECRET_KEY,
            "invoward.document-storage.download-url-ttl=5m",
            "invoward.document-storage.r2.api-call-timeout=60s",
            "invoward.document-storage.r2.api-call-attempt-timeout=30s"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(DocumentConfiguration.class, CloudflareR2Configuration.class);

    @Test
    void applicationDefaultsLoadWithDisabledStorageAndNoR2Credentials() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());

            DocumentUploadProperties upload = context.getBean(DocumentUploadProperties.class);
            assertEquals(DataSize.ofMegabytes(10), upload.maxFileSize());
            assertEquals(DataSize.ofMegabytes(20), upload.maxCombinedSize());
            assertEquals(15, upload.maxPdfPages());
            assertEquals(6000, upload.maxImageWidth());
            assertEquals(6000, upload.maxImageHeight());

            DocumentStorageProperties storage = context.getBean(DocumentStorageProperties.class);
            assertEquals(DocumentStorageProvider.DISABLED, storage.provider());
            assertEquals(1, context.getBeansOfType(DocumentStorage.class).size());
            assertTrue(context.getBean(DocumentStorage.class) instanceof DisabledDocumentStorage);
            assertEquals(Duration.ofMinutes(5), storage.downloadUrlTtl());
            assertEquals("", storage.r2().accountId());
            assertEquals("", storage.r2().bucket());
            assertEquals("", storage.r2().accessKeyId());
            assertEquals("", storage.r2().secretAccessKey());
            assertEquals(Duration.ofSeconds(60), storage.r2().apiCallTimeout());
            assertEquals(Duration.ofSeconds(30), storage.r2().apiCallAttemptTimeout());
            assertEquals(0, context.getBeansOfType(S3Client.class).size());
            assertEquals(0, context.getBeansOfType(S3Presigner.class).size());
        });
    }

    @Test
    void explicitlyDisabledProviderRegistersDisabledStorageWithoutCredentials() {
        contextRunner.withPropertyValues("invoward.document-storage.provider=disabled").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(DocumentStorage.class) instanceof DisabledDocumentStorage);
        });
    }

    @Test
    void bindsCompleteR2ConfigurationAndCreatesOneManagedAdapterAndClientPair() {
        contextRunner.withPropertyValues(
                "invoward.documents.max-file-size=5MB",
                "invoward.documents.max-combined-size=12MB",
                "invoward.documents.max-pdf-pages=8",
                "invoward.documents.max-image-width=3000",
                "invoward.documents.max-image-height=2500",
                "invoward.document-storage.provider=r2",
                "invoward.document-storage.r2.account-id=" + ACCOUNT_ID,
                "invoward.document-storage.r2.bucket=" + BUCKET,
                "invoward.document-storage.r2.access-key-id=" + ACCESS_KEY,
                "invoward.document-storage.r2.secret-access-key=" + SECRET_KEY,
                "invoward.document-storage.download-url-ttl=30s",
                "invoward.document-storage.r2.api-call-timeout=10s",
                "invoward.document-storage.r2.api-call-attempt-timeout=8s").run(context -> {
            assertNull(context.getStartupFailure());
            DocumentUploadProperties upload = context.getBean(DocumentUploadProperties.class);
            assertEquals(DataSize.ofMegabytes(5), upload.maxFileSize());
            assertEquals(DataSize.ofMegabytes(12), upload.maxCombinedSize());
            assertEquals(8, upload.maxPdfPages());
            assertEquals(3000, upload.maxImageWidth());
            assertEquals(2500, upload.maxImageHeight());

            DocumentStorageProperties storage = context.getBean(DocumentStorageProperties.class);
            assertEquals(DocumentStorageProvider.R2, storage.provider());
            assertEquals(1, context.getBeansOfType(DocumentStorage.class).size());
            assertTrue(context.getBean(DocumentStorage.class) instanceof CloudflareR2DocumentStorage);
            assertEquals(Duration.ofSeconds(30), storage.downloadUrlTtl());
            assertEquals(ACCOUNT_ID, storage.r2().accountId());
            assertEquals(BUCKET, storage.r2().bucket());
            assertEquals(ACCESS_KEY, storage.r2().accessKeyId());
            assertEquals(SECRET_KEY, storage.r2().secretAccessKey());
            assertEquals(Duration.ofSeconds(10), storage.r2().apiCallTimeout());
            assertEquals(Duration.ofSeconds(8), storage.r2().apiCallAttemptTimeout());
            assertEquals(1, context.getBeansOfType(S3Client.class).size());
            assertEquals(1, context.getBeansOfType(S3Presigner.class).size());
            assertTrue(CloudflareR2Configuration.s3Configuration().pathStyleAccessEnabled());
            assertFalse(CloudflareR2Configuration.s3Configuration().chunkedEncodingEnabled());

            var signed = context.getBean(DocumentStorage.class).createPresignedDownload(
                    "live-tests/configuration/object.pdf",
                    "application/pdf",
                    "configuration.pdf",
                    Duration.ofMinutes(1));
            URI signedUri = signed.url();
            assertTrue("fake-account-id.r2.cloudflarestorage.com".equals(signedUri.getHost()),
                    "the endpoint must be derived from the configured R2 account id");
            assertTrue(signedUri.getRawPath().startsWith(
                            "/fake-private-bucket/live-tests/configuration/object.pdf"),
                    "R2 objects must use path-style addressing with the configured private bucket");
            String signedQuery = URLDecoder.decode(signedUri.getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(signedQuery.contains("/auto/s3/aws4_request"),
                    "presigning must use the R2 auto region");
            assertTrue(signedQuery.contains("response-content-type=application/pdf"));
            assertTrue(signedQuery.contains("response-content-disposition=attachment;"));
            assertNoConfiguredSecrets(storage.toString());
            assertNoConfiguredSecrets(storage.r2().toString());
        });
    }

    @Test
    void eachRequiredR2CredentialFailsWithOnlyItsEnvironmentVariableName() {
        String[][] requiredProperties = {
                {"invoward.document-storage.r2.account-id", "R2_ACCOUNT_ID"},
                {"invoward.document-storage.r2.bucket", "R2_BUCKET"},
                {"invoward.document-storage.r2.access-key-id", "R2_ACCESS_KEY_ID"},
                {"invoward.document-storage.r2.secret-access-key", "R2_SECRET_ACCESS_KEY"}
        };

        for (String[] requiredProperty : requiredProperties) {
            contextRunner.withPropertyValues(withoutProperty(
                    COMPLETE_R2_PROPERTIES, requiredProperty[0])).run(context -> {
                Throwable failure = context.getStartupFailure();
                assertNotNull(failure, "Expected missing " + requiredProperty[1] + " to fail startup");
                String messages = exceptionMessages(failure);
                assertTrue(messages.contains(requiredProperty[1]));
                assertNoConfiguredSecrets(messages);
            });
        }
    }

    @Test
    void uploadLimitsCannotRaiseV1CeilingsOrBeNonPositive() {
        String[][] invalidProperties = {
                {"invoward.documents.max-file-size", "11MB"},
                {"invoward.documents.max-file-size", "0B"},
                {"invoward.documents.max-combined-size", "21MB"},
                {"invoward.documents.max-combined-size", "0B"},
                {"invoward.documents.max-pdf-pages", "16"},
                {"invoward.documents.max-pdf-pages", "0"},
                {"invoward.documents.max-image-width", "6001"},
                {"invoward.documents.max-image-width", "0"},
                {"invoward.documents.max-image-height", "6001"},
                {"invoward.documents.max-image-height", "0"}
        };

        for (String[] invalidProperty : invalidProperties) {
            contextRunner.withPropertyValues(invalidProperty[0] + "=" + invalidProperty[1]).run(context ->
                    assertNotNull(context.getStartupFailure(),
                            "Expected invalid value for " + invalidProperty[0] + " to fail startup"));
        }
    }

    @Test
    void signedUrlTtlMustRemainBetweenThirtySecondsAndFifteenMinutes() {
        for (String ttl : List.of("29s", "16m", "0s", "-1s")) {
            contextRunner.withPropertyValues(
                    "invoward.document-storage.download-url-ttl=" + ttl).run(context -> {
                Throwable failure = context.getStartupFailure();
                assertNotNull(failure);
                assertTrue(exceptionMessages(failure).contains("DOCUMENT_DOWNLOAD_URL_TTL"));
            });
        }

        contextRunner.withPropertyValues("invoward.document-storage.download-url-ttl=15m").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Duration.ofMinutes(15),
                    context.getBean(DocumentStorageProperties.class).downloadUrlTtl());
        });
    }

    @Test
    void r2TimeoutsMustBePositiveAndAttemptCannotExceedTotalTimeout() {
        for (String timeout : List.of("0s", "-1s")) {
            assertR2ConfigurationFails(
                    replacingProperty(COMPLETE_R2_PROPERTIES,
                            "invoward.document-storage.r2.api-call-timeout", timeout),
                    "R2_API_CALL_TIMEOUT");
            assertR2ConfigurationFails(
                    replacingProperty(COMPLETE_R2_PROPERTIES,
                            "invoward.document-storage.r2.api-call-attempt-timeout", timeout),
                    "R2_API_CALL_ATTEMPT_TIMEOUT");
        }

        assertR2ConfigurationFails(
                replacingProperty(COMPLETE_R2_PROPERTIES,
                        "invoward.document-storage.r2.api-call-timeout", "10s"),
                "R2_API_CALL_ATTEMPT_TIMEOUT");
    }

    @Test
    void unsupportedProviderFailsConfigurationBinding() {
        contextRunner.withPropertyValues("invoward.document-storage.provider=s3").run(context ->
                assertNotNull(context.getStartupFailure()));
    }

    private void assertR2ConfigurationFails(String[] properties, String safeMessagePart) {
        contextRunner.withPropertyValues(properties).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "Expected configuration failure for " + safeMessagePart);
            String messages = exceptionMessages(failure);
            assertTrue(messages.contains(safeMessagePart));
            assertNoConfiguredSecrets(messages);
        });
    }

    private static String[] withoutProperty(String[] properties, String propertyName) {
        return List.of(properties).stream()
                .filter(property -> !property.startsWith(propertyName + "="))
                .toArray(String[]::new);
    }

    private static String[] replacingProperty(String[] properties, String propertyName, String value) {
        List<String> replaced = new ArrayList<>(List.of(withoutProperty(properties, propertyName)));
        replaced.add(propertyName + "=" + value);
        return replaced.toArray(String[]::new);
    }

    private static String exceptionMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }

    private static void assertNoConfiguredSecrets(String text) {
        assertFalse(text.contains(ACCOUNT_ID));
        assertFalse(text.contains(BUCKET));
        assertFalse(text.contains(ACCESS_KEY));
        assertFalse(text.contains(SECRET_KEY));
    }
}
