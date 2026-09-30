package io.github.guillermodubon.invoward.identity.application.event;

import java.time.Instant;
import java.util.Objects;

/** Post-commit signal carrying a raw token only to the new-address email flow. */
public record EmailChangeVerificationRequested(String recipient, String rawToken, Instant expiresAt) {

    public EmailChangeVerificationRequested {
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
        return "EmailChangeVerificationRequested[recipient=[REDACTED], rawToken=[REDACTED], expiresAt=[REDACTED]]";
    }
}
