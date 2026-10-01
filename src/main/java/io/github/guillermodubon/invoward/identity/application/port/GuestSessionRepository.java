package io.github.guillermodubon.invoward.identity.application.port;

import io.github.guillermodubon.invoward.identity.application.model.GuestSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations for anonymous guest sessions. */
public interface GuestSessionRepository {

    GuestSession create(GuestSession guestSession);

    Optional<GuestSession> findActive(UUID id, Instant now);

    boolean touch(UUID id, Instant lastSeenAt);
}
