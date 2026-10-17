package io.github.guillermodubon.invoward.reporting.application.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** A short-lived private report URL; callers must never log the URL. */
public record PresignedReportDownload(URI url, Instant expiresAt) {

    public PresignedReportDownload {
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
        return "PresignedReportDownload[url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
