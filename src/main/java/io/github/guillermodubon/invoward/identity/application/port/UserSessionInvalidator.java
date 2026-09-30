package io.github.guillermodubon.invoward.identity.application.port;

import java.util.UUID;

/** Invalidates every server-side session associated with an identity account. */
@FunctionalInterface
public interface UserSessionInvalidator {

    void invalidateAll(UUID userId);
}
