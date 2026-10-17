package io.github.guillermodubon.invoward.reporting.application.model;

import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportDownloadTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-10-14T12:00:00Z");
    private static final String HASH = "b".repeat(64);

    @Test
    void mapsOnlyPublicMetadataAndRedactsTheSignedUrlFromToString() {
        URI url = URI.create("https://storage.example/private?X-Amz-Signature=private-signature");
        GeneratedReport report = report(null);

        ReportDownload result = ReportDownload.from(report,
                new PresignedReportDownload(url, GENERATED_AT.plusSeconds(300)));

        assertEquals(report.id(), result.id());
        assertEquals("application/pdf", result.contentType());
        assertEquals(128, result.sizeBytes());
        assertEquals(GENERATED_AT, result.generatedAt());
        assertEquals(url, result.downloadUrl());
        assertFalse(result.toString().contains("private-signature"));
        assertFalse(result.toString().contains("private/key.pdf"));
        assertFalse(result.toString().contains(HASH));
    }

    @Test
    void rejectsDownloadUrlThatOutlivesGuestReport() {
        Instant expiry = GENERATED_AT.plusSeconds(60);
        GeneratedReport report = report(expiry);
        PresignedReportDownload download = new PresignedReportDownload(
                URI.create("https://storage.example/private?signature=secret"), expiry.plusNanos(1));

        assertThrows(IllegalArgumentException.class, () -> ReportDownload.from(report, download));
    }

    private static GeneratedReport report(Instant expiry) {
        return new GeneratedReport(UUID.randomUUID(), UUID.randomUUID(), "private/key.pdf", "application/pdf",
                128, HASH, expiry, GENERATED_AT);
    }
}
