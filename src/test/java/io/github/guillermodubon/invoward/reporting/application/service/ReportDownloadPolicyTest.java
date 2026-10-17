package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportDownloadPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final Duration CONFIGURED_TTL = Duration.ofMinutes(5);
    private static final String HASH = "a".repeat(64);
    private final ReportDownloadPolicy policy = new ReportDownloadPolicy();

    @Test
    void registeredReportDoesNotExpireAndUsesConfiguredTtl() {
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysis(analysisId, new RegisteredUserOwner(UUID.randomUUID()), null);
        GeneratedReport report = report(analysisId, null);

        assertNull(policy.reportExpiresAt(analysis));
        assertEquals(CONFIGURED_TTL, policy.signedUrlTtl(analysis, report, CONFIGURED_TTL, NOW).orElseThrow());
    }

    @Test
    void guestReportExpiryIsFixedToAnalysisExpiryAndCapsSignedUrlTtl() {
        UUID analysisId = UUID.randomUUID();
        Instant expiry = NOW.plusSeconds(75);
        Analysis analysis = analysis(analysisId, new GuestSessionOwner(UUID.randomUUID(), expiry), expiry);
        GeneratedReport report = report(analysisId, expiry);

        assertEquals(expiry, policy.reportExpiresAt(analysis));
        assertEquals(Duration.ofSeconds(74),
                policy.signedUrlTtl(analysis, report, CONFIGURED_TTL, NOW).orElseThrow());
        assertEquals(Duration.ofMinutes(5), policy.signedUrlTtl(
                analysis, report, CONFIGURED_TTL, NOW.minusSeconds(600)).orElseThrow());
    }

    @Test
    void expiredGuestReportHasNoUsableSignedUrlTtl() {
        UUID analysisId = UUID.randomUUID();
        Instant expiry = NOW;
        Analysis analysis = analysis(analysisId, new GuestSessionOwner(UUID.randomUUID(), expiry), expiry);
        GeneratedReport report = report(analysisId, expiry);

        assertTrue(policy.signedUrlTtl(analysis, report, CONFIGURED_TTL, NOW).isEmpty());
        assertTrue(policy.signedUrlTtl(analysis, report, CONFIGURED_TTL, NOW.plusNanos(1)).isEmpty());
    }

    @Test
    void guestExpiryWithinPresigningSafetyMarginHasNoUsableSignedUrlTtl() {
        UUID analysisId = UUID.randomUUID();
        Instant expiry = NOW.plusMillis(999);
        Analysis analysis = analysis(analysisId, new GuestSessionOwner(UUID.randomUUID(), expiry), expiry);

        assertTrue(policy.signedUrlTtl(analysis, report(analysisId, expiry), CONFIGURED_TTL, NOW).isEmpty());
    }

    @Test
    void attachmentFilenameIsServerControlledAndAnalysisScoped() {
        UUID analysisId = UUID.randomUUID();

        assertEquals("invoward-report-" + analysisId + ".pdf", policy.attachmentFilename(analysisId));
    }

    @Test
    void rejectsReportWithMismatchedAnalysisOrRetention() {
        UUID analysisId = UUID.randomUUID();
        Instant expiry = NOW.plusSeconds(500);
        Analysis analysis = analysis(analysisId, new GuestSessionOwner(UUID.randomUUID(), expiry), expiry);

        assertThrows(IllegalArgumentException.class, () -> policy.signedUrlTtl(
                analysis, report(UUID.randomUUID(), expiry), CONFIGURED_TTL, NOW));
        assertThrows(IllegalArgumentException.class, () -> policy.signedUrlTtl(
                analysis, report(analysisId, expiry.plusSeconds(1)), CONFIGURED_TTL, NOW));
        assertThrows(IllegalArgumentException.class, () -> policy.signedUrlTtl(
                analysis, report(analysisId, expiry), Duration.ZERO, NOW));
        assertFalse(policy.signedUrlTtl(analysis, report(analysisId, expiry), CONFIGURED_TTL, NOW).isEmpty());
    }

    private static Analysis analysis(UUID id, AnalysisOwner owner, Instant expiresAt) {
        return new Analysis(
                id,
                owner,
                AnalysisStatus.GENERATING_REPORT,
                AnalysisReviewStatus.REVIEWED,
                AnalysisReconciliationStatus.MATCHED,
                null, null, null, null, null, null, null, null, null,
                new PriceTolerance(BigDecimal.ZERO, null), false, null, null,
                0, null, expiresAt, NOW.minusSeconds(1), NOW.minusSeconds(1));
    }

    private static GeneratedReport report(UUID analysisId, Instant expiry) {
        return new GeneratedReport(UUID.randomUUID(), analysisId, "internal/key.pdf", "application/pdf",
                128, HASH, expiry, NOW.minusSeconds(1));
    }
}
