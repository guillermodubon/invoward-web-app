package io.github.guillermodubon.invoward.analysis.domain;

import java.util.Objects;
import java.util.UUID;

/** Ownership by a registered InvoWard account. */
public record RegisteredUserOwner(UUID userId) implements AnalysisOwner {

    public RegisteredUserOwner {
        Objects.requireNonNull(userId, "userId must not be null");
    }

    @Override
    public String toString() {
        return "RegisteredUserOwner[userId=[REDACTED]]";
    }
}
