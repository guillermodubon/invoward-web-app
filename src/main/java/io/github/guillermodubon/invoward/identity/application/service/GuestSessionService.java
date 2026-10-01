package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import io.github.guillermodubon.invoward.identity.application.port.GuestSessionRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates, resolves and records activity for fixed-lifetime guest sessions. */
public class GuestSessionService {

    private final GuestSessionRepository guestSessionRepository;
    private final Duration sessionTtl;
    private final Clock clock;

    public GuestSessionService(
            GuestSessionRepository guestSessionRepository,
            Duration sessionTtl,
            Clock clock) {
        this.guestSessionRepository = Objects.requireNonNull(guestSessionRepository);
        this.sessionTtl = Objects.requireNonNull(sessionTtl);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public GuestSession create() {
        Instant now = clock.instant();
        GuestSession guestSession = GuestSession.create(
                UUID.randomUUID(), now, sessionTtl);
        return guestSessionRepository.create(guestSession);
    }

    @Transactional(readOnly = true)
    public Optional<GuestSession> findActive(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return guestSessionRepository.findActive(id, clock.instant());
    }

    @Transactional
    public boolean touch(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return guestSessionRepository.touch(id, clock.instant());
    }
}
