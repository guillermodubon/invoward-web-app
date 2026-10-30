package io.github.guillermodubon.invoward.sharing.application.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Fixed owner-selected expiry choices for a share link. */
public enum ShareLinkExpiryChoice {
    HOURS_24("24_HOURS", Duration.ofHours(24)),
    DAYS_7("7_DAYS", Duration.ofDays(7)),
    DAYS_30("30_DAYS", Duration.ofDays(30));

    private final String requestValue;
    private final Duration duration;

    ShareLinkExpiryChoice(String requestValue, Duration duration) {
        this.requestValue = requestValue;
        this.duration = duration;
    }

    /** Exact value accepted by the public create-link request contract. */
    public String requestValue() {
        return requestValue;
    }

    public Duration duration() {
        return duration;
    }

    /**
     * Calculates the requested expiry using UTC timeline arithmetic, capped by any applicable parent or report
     * expiry. A cap that leaves no usable lifetime is rejected.
     */
    public Instant calculateExpiresAt(
            Instant now,
            Instant analysisExpiresAt,
            Instant reportExpiresAt) {
        Objects.requireNonNull(now, "now must not be null");

        Instant expiresAt = now.plus(duration);
        if (analysisExpiresAt != null && analysisExpiresAt.isBefore(expiresAt)) {
            expiresAt = analysisExpiresAt;
        }
        if (reportExpiresAt != null && reportExpiresAt.isBefore(expiresAt)) {
            expiresAt = reportExpiresAt;
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Share link expiry must be after now");
        }
        return expiresAt;
    }
}
