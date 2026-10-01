package io.github.guillermodubon.invoward.identity.application.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuestSessionTest {

    private static final UUID ID = UUID.fromString("69ed3d54-a798-4fb2-b662-fc5984c43e05");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void createsSessionWithFixedExpiryAndInitialActivityTimestamp() {
        GuestSession session = GuestSession.create(ID, CREATED_AT, Duration.ofHours(24));

        assertEquals(ID, session.id());
        assertEquals(CREATED_AT, session.createdAt());
        assertEquals(CREATED_AT, session.lastSeenAt());
        assertEquals(CREATED_AT.plus(Duration.ofHours(24)), session.expiresAt());
    }

    @Test
    void rejectsInvalidIdentityAndLifetimeState() {
        assertThrows(NullPointerException.class,
                () -> new GuestSession(null, CREATED_AT.plusSeconds(1), CREATED_AT, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new GuestSession(ID, CREATED_AT, CREATED_AT, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new GuestSession(ID, CREATED_AT.plusSeconds(2), CREATED_AT.plusSeconds(2), CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> GuestSession.create(ID, CREATED_AT, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> GuestSession.create(ID, CREATED_AT, Duration.ofSeconds(-1)));
    }

    @Test
    void stringRepresentationDoesNotExposeBearerIdentifier() {
        assertTrue(GuestSession.create(ID, CREATED_AT, Duration.ofHours(24))
                .toString().contains("id=[REDACTED]"));
        assertFalse(GuestSession.create(ID, CREATED_AT, Duration.ofHours(24))
                .toString().contains(ID.toString()));
    }
}
