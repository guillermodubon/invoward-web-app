package io.github.guillermodubon.invoward.document.infrastructure.storage.r2;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException.Failure;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.infrastructure.config.DocumentStorageProperties;
import org.springframework.http.ContentDisposition;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Private Cloudflare R2 implementation of the document-storage port. */
public final class CloudflareR2DocumentStorage implements DocumentStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public CloudflareR2DocumentStorage(
            S3Client s3Client,
            S3Presigner s3Presigner,
            DocumentStorageProperties properties) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.s3Presigner = Objects.requireNonNull(s3Presigner, "s3Presigner must not be null");
        this.bucket = Objects.requireNonNull(properties, "properties must not be null").r2().bucket();
    }

    @Override
    public void store(StorageObjectUpload upload) {
        Objects.requireNonNull(upload, "upload must not be null");
        try {
            RequestBody body = RequestBody.fromFile(upload.sourceFile());
            if (body.optionalContentLength().filter(length -> length == upload.sizeBytes()).isEmpty()) {
                throw new DocumentStorageException(Failure.UNKNOWN);
            }
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(upload.storageKey())
                            .contentType(upload.contentType())
                            .contentLength(upload.sizeBytes())
                            .build(),
                    body);
        } catch (DocumentStorageException exception) {
            throw exception;
        } catch (SdkException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            throw new DocumentStorageException(Failure.UNKNOWN);
        }
    }

    @Override
    public void downloadTo(String storageKey, Path destination) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        try {
            s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(storageKey).build(),
                    ResponseTransformer.toFile(destination));
        } catch (SdkException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            throw new DocumentStorageException(Failure.UNKNOWN);
        }
    }

    @Override
    public void delete(String storageKey) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build());
        } catch (SdkException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            throw new DocumentStorageException(Failure.UNKNOWN);
        }
    }

    @Override
    public PresignedDownload createPresignedDownload(
            String storageKey,
            String contentType,
            String downloadFilename,
            Duration ttl) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        requireNonBlank(contentType, "contentType");
        requireNonBlank(downloadFilename, "downloadFilename");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be greater than 0");
        }

        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .responseContentType(contentType)
                    .responseContentDisposition(ContentDisposition.attachment()
                            .filename(downloadFilename, StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .build();
            var presignedRequest = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(getRequest)
                    .build());
            return new PresignedDownload(presignedRequest.url().toURI(), presignedRequest.expiration());
        } catch (SdkException exception) {
            throw translate(exception);
        } catch (URISyntaxException | RuntimeException exception) {
            throw new DocumentStorageException(Failure.UNKNOWN);
        }
    }

    private static DocumentStorageException translate(SdkException exception) {
        if (exception instanceof S3Exception serviceException) {
            int status = serviceException.statusCode();
            String errorCode = serviceException.awsErrorDetails() == null
                    ? ""
                    : Objects.toString(serviceException.awsErrorDetails().errorCode(), "");
            if (status == 404 || "NoSuchKey".equals(errorCode) || "NotFound".equals(errorCode)) {
                return new DocumentStorageException(Failure.NOT_FOUND);
            }
            if (status == 401 || status == 403 || "AccessDenied".equals(errorCode)) {
                return new DocumentStorageException(Failure.PERMISSION_DENIED);
            }
            if (status == 408 || status == 429 || status >= 500) {
                return new DocumentStorageException(Failure.UNAVAILABLE);
            }
        }
        if (exception instanceof SdkClientException) {
            return new DocumentStorageException(Failure.UNAVAILABLE);
        }
        return new DocumentStorageException(Failure.UNKNOWN);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    @Override
    public String toString() {
        return "CloudflareR2DocumentStorage[]";
    }
}
