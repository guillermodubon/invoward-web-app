package io.github.guillermodubon.invoward.identity.infrastructure.persistence;

import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import io.github.guillermodubon.invoward.identity.application.port.GuestSessionRepository;
import io.github.guillermodubon.invoward.identity.application.service.GuestSessionService;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@Transactional
class GuestSessionPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant TOUCHED_AT = NOW.plusSeconds(60);

    @Autowired
    private GuestSessionService guestSessionService;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void createsGuestSessionWithUuidAndFixedTwentyFourHourExpiry() {
        when(clock.instant()).thenReturn(NOW);

        GuestSession created = guestSessionService.create();

        assertNotNull(created.id());
        assertEquals(NOW, created.createdAt());
        assertEquals(NOW, created.lastSeenAt());
        assertEquals(NOW.plus(Duration.ofHours(24)), created.expiresAt());
        assertEquals(Optional.of(created), guestSessionRepository.findActive(created.id(), NOW));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.guest_sessions WHERE id = ?", Integer.class, created.id()));
    }

    @Test
    void rejectsMissingAndExpiredSessionsForLookupAndTouch() {
        when(clock.instant()).thenReturn(NOW);
        UUID missingId = UUID.randomUUID();
        assertEquals(Optional.empty(), guestSessionService.findActive(missingId));
        assertFalse(guestSessionService.touch(missingId));

        UUID expiredId = UUID.randomUUID();
        Instant createdAt = NOW.minus(Duration.ofDays(2));
        Instant expiresAt = NOW.minus(Duration.ofDays(1));
        insertSession(expiredId, expiresAt, createdAt, createdAt);

        assertEquals(Optional.empty(), guestSessionService.findActive(expiredId));
        assertFalse(guestSessionService.touch(expiredId));
        assertEquals(createdAt, lastSeenAt(expiredId));
    }

    @Test
    void touchUpdatesOnlyLastSeenAndDoesNotExtendExpiry() {
        when(clock.instant()).thenReturn(NOW, TOUCHED_AT, TOUCHED_AT);
        GuestSession created = guestSessionService.create();

        assertTrue(guestSessionService.touch(created.id()));

        GuestSession touched = guestSessionService.findActive(created.id()).orElseThrow();
        assertEquals(TOUCHED_AT, touched.lastSeenAt());
        assertEquals(created.expiresAt(), touched.expiresAt());
        assertFalse(guestSessionRepository.touch(created.id(), created.expiresAt()));
    }

    private void insertSession(UUID id, Instant expiresAt, Instant createdAt, Instant lastSeenAt) {
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, id, Timestamp.from(expiresAt), Timestamp.from(lastSeenAt), Timestamp.from(createdAt));
    }

    private Instant lastSeenAt(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT last_seen_at FROM invoward.guest_sessions WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(), id);
    }
}
