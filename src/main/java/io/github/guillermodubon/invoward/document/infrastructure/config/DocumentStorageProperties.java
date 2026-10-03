package io.github.guillermodubon.invoward.document.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "invoward.document-storage")
public record DocumentStorageProperties(
        @DefaultValue("disabled") DocumentStorageProvider provider,
        @DefaultValue("5m") Duration downloadUrlTtl,
        @DefaultValue R2 r2) {

    private static final Duration MIN_DOWNLOAD_URL_TTL = Duration.ofSeconds(30);
    private static final Duration MAX_DOWNLOAD_URL_TTL = Duration.ofMinutes(15);

    public DocumentStorageProperties {
        Objects.requireNonNull(provider, "DOCUMENT_STORAGE_PROVIDER must be configured");
        Objects.requireNonNull(r2, "R2 configuration must be present");
        if (downloadUrlTtl == null
                || downloadUrlTtl.compareTo(MIN_DOWNLOAD_URL_TTL) < 0
                || downloadUrlTtl.compareTo(MAX_DOWNLOAD_URL_TTL) > 0) {
            throw new IllegalArgumentException(
                    "DOCUMENT_DOWNLOAD_URL_TTL must be between 30s and 15m");
        }

        if (provider == DocumentStorageProvider.R2) {
            requireConfigured(r2.accountId(), "R2_ACCOUNT_ID");
            requireConfigured(r2.bucket(), "R2_BUCKET");
            requireConfigured(r2.accessKeyId(), "R2_ACCESS_KEY_ID");
            requireConfigured(r2.secretAccessKey(), "R2_SECRET_ACCESS_KEY");
        }
    }

    @Override
    public String toString() {
        return "DocumentStorageProperties[provider=" + provider
                + ", downloadUrlTtl=" + downloadUrlTtl
                + ", r2=<redacted>]";
    }

    private static void requireConfigured(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "R2 document storage provider requires " + environmentVariable);
        }
    }

    public record R2(
            @DefaultValue("") String accountId,
            @DefaultValue("") String bucket,
            @DefaultValue("") String accessKeyId,
            @DefaultValue("") String secretAccessKey,
            @DefaultValue("60s") Duration apiCallTimeout,
            @DefaultValue("30s") Duration apiCallAttemptTimeout) {

        public R2 {
            Objects.requireNonNull(apiCallTimeout, "R2_API_CALL_TIMEOUT must be configured");
            Objects.requireNonNull(apiCallAttemptTimeout,
                    "R2_API_CALL_ATTEMPT_TIMEOUT must be configured");
            if (apiCallTimeout.isZero() || apiCallTimeout.isNegative()) {
                throw new IllegalArgumentException("R2_API_CALL_TIMEOUT must be greater than 0");
            }
            if (apiCallAttemptTimeout.isZero() || apiCallAttemptTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "R2_API_CALL_ATTEMPT_TIMEOUT must be greater than 0");
            }
            if (apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
                throw new IllegalArgumentException(
                        "R2_API_CALL_ATTEMPT_TIMEOUT must not exceed R2_API_CALL_TIMEOUT");
            }
        }

        @Override
        public String toString() {
            return "R2[credentials=<redacted>, apiCallTimeout=" + apiCallTimeout
                    + ", apiCallAttemptTimeout=" + apiCallAttemptTimeout + "]";
        }
    }
}
