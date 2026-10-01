package io.github.guillermodubon.invoward.identity.application.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistence-neutral state for an anonymous guest session. */
public record GuestSession(UUID id, Instant expiresAt, Instant lastSeenAt, Instant createdAt) {

    public GuestSession {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (lastSeenAt.isBefore(createdAt) || !lastSeenAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("lastSeenAt must be within the guest session lifetime");
        }
    }

    public static GuestSession create(UUID id, Instant createdAt, Duration ttl) {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be greater than zero");
        }
        return new GuestSession(id, createdAt.plus(ttl), createdAt, createdAt);
    }

    @Override
    public String toString() {
        return "GuestSession[id=[REDACTED], expiresAt=" + expiresAt
                + ", lastSeenAt=" + lastSeenAt + ", createdAt=" + createdAt + "]";
    }
}
