package io.github.guillermodubon.invoward.reporting.api.model;

import io.github.guillermodubon.invoward.reporting.application.model.ReportDownload;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Public report metadata and short-lived download link; storage identifiers and hashes are excluded. */
public record GeneratedReportResponse(
        UUID id,
        String contentType,
        long sizeBytes,
        Instant generatedAt,
        Instant expiresAt,
        URI downloadUrl,
        Instant downloadUrlExpiresAt) {

    public GeneratedReportResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        Objects.requireNonNull(downloadUrl, "downloadUrl must not be null");
        Objects.requireNonNull(downloadUrlExpiresAt, "downloadUrlExpiresAt must not be null");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be greater than 0");
        }
    }

    public static GeneratedReportResponse from(ReportDownload report) {
        Objects.requireNonNull(report, "report must not be null");
        return new GeneratedReportResponse(
                report.id(), report.contentType(), report.sizeBytes(), report.generatedAt(), report.expiresAt(),
                report.downloadUrl(), report.downloadUrlExpiresAt());
    }
}
