package io.github.guillermodubon.invoward.sharing.api.model;

import io.github.guillermodubon.invoward.sharing.application.model.CreatedShareLink;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One-time creation response; the public URL is a bearer credential and is never logged. */
public record CreatedShareLinkResponse(
        UUID id,
        String publicUrl,
        String expiresIn,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {

    public CreatedShareLinkResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(publicUrl, "publicUrl must not be null");
        Objects.requireNonNull(expiresIn, "expiresIn must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public static CreatedShareLinkResponse from(CreatedShareLink created) {
        Objects.requireNonNull(created, "created must not be null");
        return new CreatedShareLinkResponse(
                created.id(), created.publicUrl(), created.expiresIn().requestValue(),
                created.createdAt(), created.expiresAt(), created.revokedAt());
    }

    @Override
    public String toString() {
        return "CreatedShareLinkResponse[id=[REDACTED], publicUrl=[REDACTED], expiresIn=" + expiresIn
                + ", createdAt=" + createdAt + ", expiresAt=" + expiresAt + ", revokedAt=" + revokedAt + "]";
    }
}
