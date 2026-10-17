package io.github.guillermodubon.invoward.reporting.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneratedReportTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void acceptsValidPdfMetadataWithOptionalExpiry() {
        assertDoesNotThrow(() -> report(null));
        assertDoesNotThrow(() -> report(GENERATED_AT.plusSeconds(86_400)));
    }

    @Test
    void rejectsMissingIdentityStorageAndGenerationMetadata() {
        assertThrows(NullPointerException.class, () -> new GeneratedReport(
                null, UUID.randomUUID(), "reports/a.pdf", "application/pdf", 1,
                "a".repeat(64), null, GENERATED_AT));
        assertThrows(NullPointerException.class, () -> new GeneratedReport(
                UUID.randomUUID(), null, "reports/a.pdf", "application/pdf", 1,
                "a".repeat(64), null, GENERATED_AT));
        assertThrows(NullPointerException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), null, "application/pdf", 1,
                "a".repeat(64), null, GENERATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "  ", "application/pdf", 1,
                "a".repeat(64), null, GENERATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "k".repeat(513), "application/pdf", 1,
                "a".repeat(64), null, GENERATED_AT));
        assertThrows(NullPointerException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "reports/a.pdf", null, 1,
                "a".repeat(64), null, GENERATED_AT));
        assertThrows(NullPointerException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "reports/a.pdf", "application/pdf", 1,
                null, null, GENERATED_AT));
        assertThrows(NullPointerException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "reports/a.pdf", "application/pdf", 1,
                "a".repeat(64), null, null));
    }

    @Test
    void enforcesPdfPositiveSizeAndLowercaseSha256() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "reports/a.pdf", "text/plain", 1,
                "a".repeat(64), null, GENERATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "reports/a.pdf", "application/pdf", 0,
                "a".repeat(64), null, GENERATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "reports/a.pdf", "application/pdf", -1,
                "a".repeat(64), null, GENERATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "reports/a.pdf", "application/pdf", 1,
                "A".repeat(64), null, GENERATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "reports/a.pdf", "application/pdf", 1,
                "a".repeat(63), null, GENERATED_AT));
    }

    @Test
    void toStringDoesNotExposeStorageKeyOrSha256() {
        GeneratedReport report = report(null);

        assertFalse(report.toString().contains(report.storageKey()));
        assertFalse(report.toString().contains(report.sha256()));
    }

    private static GeneratedReport report(Instant expiresAt) {
        return new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "users/x/analyses/y/reports/z.pdf",
                "application/pdf", 12_345L, "a".repeat(64), expiresAt, GENERATED_AT);
    }
}
