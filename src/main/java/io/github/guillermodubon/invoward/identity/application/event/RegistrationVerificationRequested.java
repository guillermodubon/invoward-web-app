package io.github.guillermodubon.invoward.identity.application.event;

import java.time.Instant;
import java.util.Objects;

/** In-process registration event carrying temporary verification material. */
public record RegistrationVerificationRequested(
        String recipient,
        String rawVerificationToken,
        Instant expiresAt) {

    public RegistrationVerificationRequested {
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient must not be blank");
        }
        if (rawVerificationToken == null || rawVerificationToken.isBlank()) {
            throw new IllegalArgumentException("rawVerificationToken must not be blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    @Override
    public String toString() {
        return "RegistrationVerificationRequested[recipient=[REDACTED], "
                + "rawVerificationToken=[REDACTED], expiresAt=[REDACTED]]";
    }
}
