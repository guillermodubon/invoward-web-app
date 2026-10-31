package io.github.guillermodubon.invoward.sharing.api.model;

import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkListItem;
import io.github.guillermodubon.invoward.sharing.domain.ShareLinkStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Public contract for owner-visible share-link metadata, excluding all bearer-token material. */
public record ShareLinkSummaryResponse(
        UUID id,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        ShareLinkStatus status) {

    public ShareLinkSummaryResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static ShareLinkSummaryResponse from(ShareLinkListItem link) {
        Objects.requireNonNull(link, "link must not be null");
        return new ShareLinkSummaryResponse(link.id(), link.createdAt(), link.expiresAt(), link.revokedAt(),
                link.status());
    }
}
