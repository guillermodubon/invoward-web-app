package io.github.guillermodubon.invoward.sharing.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Provider-neutral persisted share-link metadata. The raw capability token is never part of this model. */
public record ShareLink(
        UUID id,
        UUID analysisId,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {

    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public ShareLink {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        if (!TOKEN_HASH_PATTERN.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException("tokenHash must be 64 lowercase hexadecimal characters");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (revokedAt != null && revokedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("revokedAt must not be before createdAt");
        }
    }

    public boolean isActive(Instant now) {
        return isActive(now, true);
    }

    /** Parent conditions let application callers include current Analysis/report validity without coupling modules. */
    public boolean isActive(Instant now, boolean parentConditionsSatisfied) {
        Objects.requireNonNull(now, "now must not be null");
        return revokedAt == null && now.isBefore(expiresAt) && parentConditionsSatisfied;
    }

    public ShareLinkStatus statusAt(Instant now) {
        return statusAt(now, true);
    }

    /** Derives status while preserving revocation as the highest-priority durable state. */
    public ShareLinkStatus statusAt(Instant now, boolean parentConditionsSatisfied) {
        Objects.requireNonNull(now, "now must not be null");
        if (revokedAt != null) {
            return ShareLinkStatus.REVOKED;
        }
        if (!now.isBefore(expiresAt) || !parentConditionsSatisfied) {
            return ShareLinkStatus.EXPIRED;
        }
        return ShareLinkStatus.ACTIVE;
    }

    /** Returns a revoked copy; repeated revocation preserves the original timestamp. */
    public ShareLink revoke(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (revokedAt != null) {
            return this;
        }
        if (now.isBefore(createdAt)) {
            throw new IllegalArgumentException("revokedAt must not be before createdAt");
        }
        return new ShareLink(id, analysisId, tokenHash, createdAt, expiresAt, now);
    }

    @Override
    public String toString() {
        return "ShareLink[id=[REDACTED], analysisId=[REDACTED], tokenHash=[REDACTED]]";
    }
}
