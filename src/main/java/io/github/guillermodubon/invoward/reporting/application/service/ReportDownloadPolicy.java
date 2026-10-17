package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Applies the fixed report-retention and presigned-download lifetime rules. */
public final class ReportDownloadPolicy {

    /*
     * The signed URL is created after this policy returns a duration. Reserve a small
     * handoff margin so the time spent producing the signature cannot make a guest
     * URL outlive the fixed Analysis/report retention instant.
     */
    private static final Duration PRESIGN_EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(1);

    /** Returns the Analysis' fixed expiry; registered reports do not expire. */
    public Instant reportExpiresAt(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        return switch (analysis.owner()) {
            case RegisteredUserOwner ignored -> null;
            case GuestSessionOwner guest -> guest.expiresAt();
        };
    }

    /** Returns empty when the report is already expired, so callers can map it to the same 404 as absence. */
    public Optional<Duration> signedUrlTtl(
            Analysis analysis,
            GeneratedReport report,
            Duration configuredTtl,
            Instant now) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(configuredTtl, "configuredTtl must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!analysis.id().equals(report.analysisId())) {
            throw new IllegalArgumentException("report must belong to the supplied Analysis");
        }
        if (configuredTtl.isZero() || configuredTtl.isNegative()) {
            throw new IllegalArgumentException("configuredTtl must be positive");
        }

        Instant expectedExpiry = reportExpiresAt(analysis);
        if (!Objects.equals(expectedExpiry, report.expiresAt())) {
            throw new IllegalArgumentException("report expiry must match its Analysis retention policy");
        }
        if (expectedExpiry == null) {
            return Optional.of(configuredTtl);
        }

        Duration remaining = Duration.between(now, expectedExpiry).minus(PRESIGN_EXPIRY_SAFETY_MARGIN);
        if (remaining.isZero() || remaining.isNegative()) {
            return Optional.empty();
        }
        return Optional.of(remaining.compareTo(configuredTtl) < 0 ? remaining : configuredTtl);
    }

    /** Builds a server-controlled attachment filename containing only the Analysis identifier. */
    public String attachmentFilename(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return "invoward-report-" + analysisId + ".pdf";
    }
}
