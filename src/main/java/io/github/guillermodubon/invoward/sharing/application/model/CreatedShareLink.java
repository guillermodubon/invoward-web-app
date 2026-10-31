package io.github.guillermodubon.invoward.sharing.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One-time creation result. The URL contains a bearer token and must not be logged. */
public record CreatedShareLink(
        UUID id,
        String publicUrl,
        ShareLinkExpiryChoice expiresIn,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {

    public CreatedShareLink {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(publicUrl, "publicUrl must not be null");
        Objects.requireNonNull(expiresIn, "expiresIn must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (publicUrl.isBlank() || !expiresAt.isAfter(createdAt) || revokedAt != null) {
            throw new IllegalArgumentException("Created share-link result is invalid.");
        }
    }

    @Override
    public String toString() {
        return "CreatedShareLink[id=[REDACTED], publicUrl=[REDACTED], expiresIn="
                + expiresIn.requestValue() + ", createdAt=" + createdAt + ", expiresAt=" + expiresAt + "]";
    }
}
