package io.github.guillermodubon.invoward.reporting.application.model;

import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe report metadata paired with a short-lived private download URL. */
public record ReportDownload(
        UUID id,
        String contentType,
        long sizeBytes,
        Instant generatedAt,
        Instant expiresAt,
        URI downloadUrl,
        Instant downloadUrlExpiresAt) {

    public ReportDownload {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        Objects.requireNonNull(downloadUrl, "downloadUrl must not be null");
        Objects.requireNonNull(downloadUrlExpiresAt, "downloadUrlExpiresAt must not be null");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be greater than 0");
        }
        if (!downloadUrl.isAbsolute() || downloadUrl.getHost() == null
                || !"https".equalsIgnoreCase(downloadUrl.getScheme()) || downloadUrl.getUserInfo() != null) {
            throw new IllegalArgumentException("downloadUrl must be an absolute HTTPS URI without user information");
        }
        if (expiresAt != null && downloadUrlExpiresAt.isAfter(expiresAt)) {
            throw new IllegalArgumentException("download URL must not outlive the report");
        }
    }

    public static ReportDownload from(GeneratedReport report, PresignedReportDownload download) {
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(download, "download must not be null");
        return new ReportDownload(
                report.id(),
                report.contentType(),
                report.sizeBytes(),
                report.generatedAt(),
                report.expiresAt(),
                download.url(),
                download.expiresAt());
    }

    @Override
    public String toString() {
        return "ReportDownload[id=" + id + ", contentType=" + contentType + ", sizeBytes=" + sizeBytes
                + ", generatedAt=" + generatedAt + ", expiresAt=" + expiresAt
                + ", downloadUrl=<redacted>, downloadUrlExpiresAt=" + downloadUrlExpiresAt + "]";
    }
}
