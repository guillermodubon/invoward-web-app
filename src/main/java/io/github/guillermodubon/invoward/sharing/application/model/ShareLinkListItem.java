package io.github.guillermodubon.invoward.sharing.application.model;

import io.github.guillermodubon.invoward.sharing.domain.ShareLinkStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe metadata projection for one link; it intentionally has no token or owner fields. */
public record ShareLinkListItem(
        UUID id,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        ShareLinkStatus status) {

    public ShareLinkListItem {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (!expiresAt.isAfter(createdAt) || (revokedAt != null && revokedAt.isBefore(createdAt))) {
            throw new IllegalArgumentException("Share-link metadata is inconsistent.");
        }
    }
}
