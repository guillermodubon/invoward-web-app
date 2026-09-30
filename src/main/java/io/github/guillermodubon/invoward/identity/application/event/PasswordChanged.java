package io.github.guillermodubon.invoward.identity.application.event;

import java.util.Objects;
import java.util.UUID;

/** Post-commit signal to invalidate account sessions and send a security notification. */
public record PasswordChanged(UUID userId, String recipient) {

    public PasswordChanged {
        Objects.requireNonNull(userId, "userId must not be null");
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient must not be blank");
        }
    }

    @Override
    public String toString() {
        return "PasswordChanged[userId=" + userId + ", recipient=[REDACTED]]";
    }
}
