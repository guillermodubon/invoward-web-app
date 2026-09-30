package io.github.guillermodubon.invoward.identity.application.event;

import java.time.Instant;
import java.util.Objects;

/** In-process password reset request; raw token exists only for post-commit email delivery. */
public record PasswordResetRequested(String recipient, String rawToken, Instant expiresAt) {

    public PasswordResetRequested {
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient must not be blank");
        }
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("rawToken must not be blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    @Override
    public String toString() {
        return "PasswordResetRequested[recipient=[REDACTED], rawToken=[REDACTED], expiresAt=[REDACTED]]";
    }
}
