package io.github.guillermodubon.invoward.document.infrastructure.storage.r2;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException.Failure;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.infrastructure.config.DocumentStorageProperties;
import io.github.guillermodubon.invoward.document.infrastructure.config.DocumentStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CloudflareR2DocumentStorageTest {

    private static final String BUCKET = "private-test-bucket";
    private static final String STORAGE_KEY = "users/test/analyses/test/documents/random.pdf";

    @TempDir
    Path temporaryDirectory;

    private S3Client s3Client;
    private S3Presigner s3Presigner;
    private CloudflareR2DocumentStorage storage;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        s3Presigner = S3Presigner.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create("https://test-account.r2.cloudflarestorage.com"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
        storage = new CloudflareR2DocumentStorage(s3Client, s3Presigner, properties());
    }

    @AfterEach
    void closePresigner() {
        s3Presigner.close();
    }

    @Test
    void storesFileBackedObjectWithKnownLengthCanonicalTypeAndNoPublicAcl() throws Exception {
        byte[] content = "synthetic document payload".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("staged.pdf");
        Files.write(source, content);

        storage.store(new StorageObjectUpload(STORAGE_KEY, source, "application/pdf", content.length));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(request.capture(), body.capture());
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals(STORAGE_KEY, request.getValue().key());
        assertEquals("application/pdf", request.getValue().contentType());
        assertEquals((long) content.length, request.getValue().contentLength());
        assertNull(request.getValue().acl(), "objects must remain private; no ACL may be set");
        assertEquals((long) content.length, body.getValue().optionalContentLength().orElseThrow());
    }

    @Test
    void downloadsThroughTheSdkFileTransformerWithoutBufferingTheObjectInApplicationCode() throws Exception {
        byte[] content = "streamed synthetic document".getBytes(StandardCharsets.UTF_8);
        Path destination = temporaryDirectory.resolve("downloaded.pdf");
        doAnswer(invocation -> {
            ResponseTransformer<GetObjectResponse, GetObjectResponse> transformer = invocation.getArgument(1);
            return transformer.transform(
                    GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(content)));
        }).when(s3Client).getObject(
                any(GetObjectRequest.class),
                ArgumentMatchers.<ResponseTransformer<GetObjectResponse, GetObjectResponse>>any());

        storage.downloadTo(STORAGE_KEY, destination);

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(
                request.capture(),
                ArgumentMatchers.<ResponseTransformer<GetObjectResponse, GetObjectResponse>>any());
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals(STORAGE_KEY, request.getValue().key());
        assertArrayEquals(content, Files.readAllBytes(destination));
    }

    @Test
    void deletesOnlyTheConfiguredBucketAndSuppliedObjectKey() {
        storage.delete(STORAGE_KEY);

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(request.capture());
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals(STORAGE_KEY, request.getValue().key());
    }

    @Test
    void presignsAnExactGetWithCanonicalResponseHeadersAndRequestedDuration() {
        Duration ttl = Duration.ofSeconds(90);
        Instant before = Instant.now();

        PresignedDownload download = storage.createPresignedDownload(
                STORAGE_KEY, "application/pdf", "invoice #1.pdf", ttl);

        URI signedUri = download.url();
        assertTrue("test-account.r2.cloudflarestorage.com".equals(signedUri.getHost()));
        assertTrue(signedUri.getRawPath().startsWith("/" + BUCKET + "/" + STORAGE_KEY));
        String query = URLDecoder.decode(signedUri.getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(query.contains("X-Amz-Expires=90"));
        assertTrue(query.contains("/auto/s3/aws4_request"));
        assertTrue(query.contains("response-content-type=application/pdf"));
        assertTrue(query.contains("response-content-disposition=attachment;"));
        assertTrue(download.expiresAt().isAfter(before));
        assertFalse(download.toString().contains(signedUri.toString()));
    }

    @Test
    void mapsProviderNotFoundAndPermissionFailuresToSafePortErrors() {
        doThrow(serviceFailure(404, "private bucket and signed request details"))
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        DocumentStorageException notFound = assertThrows(
                DocumentStorageException.class, () -> storage.delete(STORAGE_KEY));
        assertEquals(Failure.NOT_FOUND, notFound.failure());
        assertFalse(notFound.getMessage().contains("private bucket"));
        assertFalse(notFound.toString().contains("signed request"));

        doThrow(serviceFailure(403, "credential and provider response details"))
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        DocumentStorageException denied = assertThrows(
                DocumentStorageException.class, () -> storage.delete(STORAGE_KEY));
        assertEquals(Failure.PERMISSION_DENIED, denied.failure());
        assertFalse(denied.getMessage().contains("credential"));
    }

    @Test
    void mapsProviderOutageWithoutLeakingSdkDiagnostic() {
        doThrow(serviceFailure(503, "do-not-expose-provider-response"))
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        DocumentStorageException exception = assertThrows(
                DocumentStorageException.class, () -> storage.delete(STORAGE_KEY));

        assertEquals(Failure.UNAVAILABLE, exception.failure());
        assertFalse(exception.getMessage().contains("do-not-expose-provider-response"));
        assertFalse(exception.toString().contains("do-not-expose-provider-response"));
        assertNull(exception.getCause());
    }

    @Test
    void mapsClientTransportErrorsWithoutExposingSdkMessage() {
        doThrow(SdkClientException.create("do-not-expose-access-key-or-endpoint"))
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        DocumentStorageException exception = assertThrows(
                DocumentStorageException.class, () -> storage.delete(STORAGE_KEY));

        assertEquals(Failure.UNAVAILABLE, exception.failure());
        assertFalse(exception.getMessage().contains("access-key"));
        assertFalse(exception.toString().contains("endpoint"));
        assertNull(exception.getCause());
    }

    @Test
    void rejectsFileSizeDriftWithoutCallingTheProvider() throws Exception {
        Path source = temporaryDirectory.resolve("changed.pdf");
        Files.writeString(source, "actual bytes");

        DocumentStorageException exception = assertThrows(
                DocumentStorageException.class,
                () -> storage.store(new StorageObjectUpload(
                        STORAGE_KEY, source, "application/pdf", 1)));

        assertEquals(Failure.UNKNOWN, exception.failure());
        org.mockito.Mockito.verifyNoInteractions(s3Client);
    }

    private static S3Exception serviceFailure(int status, String safeTestOnlyMessage) {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .message(safeTestOnlyMessage)
                .build();
    }

    private static DocumentStorageProperties properties() {
        return new DocumentStorageProperties(
                DocumentStorageProvider.R2,
                Duration.ofMinutes(5),
                new DocumentStorageProperties.R2(
                        "test-account",
                        BUCKET,
                        "test-access-key",
                        "test-secret-key",
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30)));
    }
}
