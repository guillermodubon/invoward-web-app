package io.github.guillermodubon.invoward.identity.application.event;

import java.util.Objects;
import java.util.UUID;

/** Post-commit signal to expire account sessions and notify the previous address. */
public record EmailChanged(UUID userId, String previousEmail) {

    public EmailChanged {
        Objects.requireNonNull(userId, "userId must not be null");
        if (previousEmail == null || previousEmail.isBlank()) {
            throw new IllegalArgumentException("previousEmail must not be blank");
        }
    }

    @Override
    public String toString() {
        return "EmailChanged[userId=" + userId + ", previousEmail=[REDACTED]]";
    }
}
