package io.github.guillermodubon.invoward.analysis.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisOwnerTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void registeredOwnerRequiresUserIdAndRedactsItFromStringRepresentation() {
        UUID userId = UUID.randomUUID();

        RegisteredUserOwner owner = new RegisteredUserOwner(userId);

        assertEquals(userId, owner.userId());
        assertFalse(owner.toString().contains(userId.toString()));
        assertThrows(NullPointerException.class, () -> new RegisteredUserOwner(null));
    }

    @Test
    void guestOwnerRequiresIdAndExpiryAndOnlyFactoryAcceptsFutureExpiry() {
        UUID guestSessionId = UUID.randomUUID();
        Instant expiry = NOW.plusSeconds(60);

        GuestSessionOwner owner = GuestSessionOwner.create(guestSessionId, expiry, NOW);

        assertEquals(guestSessionId, owner.guestSessionId());
        assertEquals(expiry, owner.expiresAt());
        assertThrows(IllegalArgumentException.class,
                () -> GuestSessionOwner.create(guestSessionId, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> GuestSessionOwner.create(guestSessionId, NOW.minusSeconds(1), NOW));
        assertThrows(NullPointerException.class,
                () -> GuestSessionOwner.create(guestSessionId, expiry, null));
        assertThrows(NullPointerException.class,
                () -> GuestSessionOwner.create(guestSessionId, null, NOW));
        assertThrows(NullPointerException.class,
                () -> new GuestSessionOwner(null, expiry));
        assertThrows(NullPointerException.class,
                () -> new GuestSessionOwner(guestSessionId, null));
        assertFalse(owner.toString().contains(guestSessionId.toString()));
    }

    @Test
    void ownerIsSealedToTheTwoExclusiveOwnershipRepresentations() {
        assertTrue(AnalysisOwner.class.isSealed());
        assertEquals(Set.of(RegisteredUserOwner.class, GuestSessionOwner.class),
                Set.of(AnalysisOwner.class.getPermittedSubclasses()));
    }
}
