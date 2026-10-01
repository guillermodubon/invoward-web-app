package io.github.guillermodubon.invoward.analysis.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Ownership by a temporary guest session. The session UUID is a bearer credential. */
public record GuestSessionOwner(UUID guestSessionId, Instant expiresAt) implements AnalysisOwner {

    public GuestSessionOwner {
        Objects.requireNonNull(guestSessionId, "guestSessionId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /** Creates a new owner only while its fixed guest-session lifetime is still active. */
    public static GuestSessionOwner create(UUID guestSessionId, Instant expiresAt, Instant now) {
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Guest session expiry must be after creation time");
        }
        return new GuestSessionOwner(guestSessionId, expiresAt);
    }

    @Override
    public String toString() {
        return "GuestSessionOwner[guestSessionId=[REDACTED], expiresAt=" + expiresAt + "]";
    }
}
