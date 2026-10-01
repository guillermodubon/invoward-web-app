package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import io.github.guillermodubon.invoward.identity.application.port.GuestSessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    private final GuestSessionRepository repository = mock(GuestSessionRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final GuestSessionService service = new GuestSessionService(
            repository, Duration.ofHours(24), clock);

    @Test
    void createsAndPersistsAUniqueSessionWithConfiguredFixedTtl() {
        when(repository.create(any(GuestSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GuestSession created = service.create();

        assertNotNull(created.id());
        assertEquals(NOW, created.createdAt());
        assertEquals(NOW, created.lastSeenAt());
        assertEquals(NOW.plus(Duration.ofHours(24)), created.expiresAt());
        verify(repository).create(created);
    }

    @Test
    void resolvesOnlyTheActiveSessionAtTheInjectedClockTime() {
        GuestSession active = GuestSession.create(UUID.randomUUID(), NOW, Duration.ofHours(24));
        when(repository.findActive(active.id(), NOW)).thenReturn(Optional.of(active));

        assertEquals(Optional.of(active), service.findActive(active.id()));
        verify(repository).findActive(active.id(), NOW);
    }

    @Test
    void safelyReportsMissingSessionsAndDelegatesTouchUsingCurrentTime() {
        UUID unknownId = UUID.randomUUID();
        when(repository.findActive(unknownId, NOW)).thenReturn(Optional.empty());
        when(repository.touch(unknownId, NOW)).thenReturn(false);

        assertEquals(Optional.empty(), service.findActive(unknownId));
        assertFalse(service.touch(unknownId));
        verify(repository).touch(unknownId, NOW);
    }

    @Test
    void reportsWhetherAnActiveSessionWasTouched() {
        UUID id = UUID.randomUUID();
        when(repository.touch(id, NOW)).thenReturn(true);

        assertTrue(service.touch(id));
        verify(repository).touch(id, NOW);
    }
}
