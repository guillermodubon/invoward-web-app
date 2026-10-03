package io.github.guillermodubon.invoward.document.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.guillermodubon.invoward.document.infrastructure.storage.r2.CloudflareR2DocumentStorage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.Objects;

/** Owns the managed S3-compatible clients used by the private Cloudflare R2 adapter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "invoward.document-storage",
        name = "provider",
        havingValue = "r2")
public class CloudflareR2Configuration {

    @Bean
    public AwsCredentialsProvider cloudflareR2CredentialsProvider(DocumentStorageProperties properties) {
        DocumentStorageProperties.R2 r2 = Objects.requireNonNull(properties).r2();
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(r2.accessKeyId(), r2.secretAccessKey()));
    }

    @Bean(destroyMethod = "close")
    public S3Client cloudflareR2S3Client(
            DocumentStorageProperties properties,
            AwsCredentialsProvider cloudflareR2CredentialsProvider) {
        DocumentStorageProperties.R2 r2 = Objects.requireNonNull(properties).r2();
        try {
            return S3Client.builder()
                    .endpointOverride(endpoint(r2.accountId()))
                    .region(Region.of("auto"))
                    .credentialsProvider(cloudflareR2CredentialsProvider)
                    .serviceConfiguration(s3Configuration())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(r2.apiCallTimeout())
                            .apiCallAttemptTimeout(r2.apiCallAttemptTimeout())
                            .build())
                    .build();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("R2 document storage client configuration is invalid");
        }
    }

    @Bean(destroyMethod = "close")
    public S3Presigner cloudflareR2S3Presigner(
            DocumentStorageProperties properties,
            AwsCredentialsProvider cloudflareR2CredentialsProvider) {
        DocumentStorageProperties.R2 r2 = Objects.requireNonNull(properties).r2();
        try {
            return S3Presigner.builder()
                    .endpointOverride(endpoint(r2.accountId()))
                    .region(Region.of("auto"))
                    .credentialsProvider(cloudflareR2CredentialsProvider)
                    .serviceConfiguration(s3Configuration())
                    .build();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("R2 document storage presigner configuration is invalid");
        }
    }

    @Bean
    public CloudflareR2DocumentStorage cloudflareR2DocumentStorage(
            S3Client cloudflareR2S3Client,
            S3Presigner cloudflareR2S3Presigner,
            DocumentStorageProperties properties) {
        return new CloudflareR2DocumentStorage(cloudflareR2S3Client, cloudflareR2S3Presigner, properties);
    }

    static S3Configuration s3Configuration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();
    }

    private static URI endpoint(String accountId) {
        return URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
    }
}
