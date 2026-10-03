package io.github.guillermodubon.invoward.document.application.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** A temporary download address and its expiration; callers must never log the address. */
public record PresignedDownload(URI url, Instant expiresAt) {

    public PresignedDownload {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!url.isAbsolute() || url.getHost() == null || !"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("url must be an absolute HTTPS URI");
        }
        if (url.getUserInfo() != null) {
            throw new IllegalArgumentException("url must not contain user information");
        }
    }

    @Override
    public String toString() {
        return "PresignedDownload[url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
