package io.github.guillermodubon.invoward.sharing.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareLinkTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-24T18:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plusSeconds(3600);
    private static final String TOKEN_HASH = "a".repeat(64);

    @Test
    void startsActiveUntilTheExactExpiryBoundary() {
        ShareLink link = newShareLink();

        assertTrue(link.isActive(CREATED_AT));
        assertTrue(link.isActive(EXPIRES_AT.minusNanos(1)));
        assertEquals(ShareLinkStatus.ACTIVE, link.statusAt(CREATED_AT));
        assertFalse(link.isActive(EXPIRES_AT));
        assertEquals(ShareLinkStatus.EXPIRED, link.statusAt(EXPIRES_AT));
        assertFalse(link.isActive(EXPIRES_AT.minusSeconds(1), false));
        assertEquals(ShareLinkStatus.EXPIRED, link.statusAt(EXPIRES_AT.minusSeconds(1), false));
    }

    @Test
    void revocationIsIdempotentAndKeepsTheOriginalTimestamp() {
        ShareLink link = newShareLink();
        Instant revokedAt = CREATED_AT.plusSeconds(10);

        ShareLink revoked = link.revoke(revokedAt);

        assertNotSame(link, revoked);
        assertEquals(revokedAt, revoked.revokedAt());
        assertFalse(revoked.isActive(revokedAt.minusSeconds(1)));
        assertEquals(ShareLinkStatus.REVOKED, revoked.statusAt(revokedAt));
        assertSame(revoked, revoked.revoke(revokedAt.plusSeconds(30)));
        assertEquals(revokedAt, revoked.revoke(revokedAt.plusSeconds(30)).revokedAt());
        assertEquals(ShareLinkStatus.REVOKED, revoked.statusAt(EXPIRES_AT, false));
    }

    @Test
    void permitsAdministrativeRevocationAfterExpiry() {
        ShareLink revoked = newShareLink().revoke(EXPIRES_AT.plusSeconds(1));

        assertEquals(EXPIRES_AT.plusSeconds(1), revoked.revokedAt());
        assertEquals(ShareLinkStatus.REVOKED, revoked.statusAt(EXPIRES_AT.plusSeconds(1)));
    }

    @Test
    void revokingOneLinkDoesNotChangeAnotherIndependentLink() {
        UUID analysisId = UUID.randomUUID();
        ShareLink first = new ShareLink(UUID.randomUUID(), analysisId, TOKEN_HASH, CREATED_AT, EXPIRES_AT, null);
        ShareLink second = new ShareLink(UUID.randomUUID(), analysisId, "b".repeat(64), CREATED_AT, EXPIRES_AT, null);

        ShareLink revokedFirst = first.revoke(CREATED_AT.plusSeconds(1));

        assertEquals(ShareLinkStatus.REVOKED, revokedFirst.statusAt(CREATED_AT.plusSeconds(1)));
        assertEquals(ShareLinkStatus.ACTIVE, second.statusAt(CREATED_AT.plusSeconds(1)));
        assertTrue(second.isActive(CREATED_AT.plusSeconds(1)));
    }

    @Test
    void rejectsMalformedHashAndInvalidTimestamps() {
        UUID id = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();

        for (String hash : new String[]{"A".repeat(64), "f".repeat(63), "g".repeat(64), " ".repeat(64)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ShareLink(id, analysisId, hash, CREATED_AT, EXPIRES_AT, null));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ShareLink(id, analysisId, TOKEN_HASH, CREATED_AT, CREATED_AT, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ShareLink(id, analysisId, TOKEN_HASH, CREATED_AT, EXPIRES_AT, CREATED_AT.minusNanos(1)));
        assertEquals(CREATED_AT, new ShareLink(
                id, analysisId, TOKEN_HASH, CREATED_AT, EXPIRES_AT, CREATED_AT).revokedAt());
        assertThrows(IllegalArgumentException.class,
                () -> newShareLink().revoke(CREATED_AT.minusNanos(1)));
    }

    @Test
    void requiresPersistedIdentityAndTimes() {
        assertThrows(NullPointerException.class,
                () -> new ShareLink(null, UUID.randomUUID(), TOKEN_HASH, CREATED_AT, EXPIRES_AT, null));
        assertThrows(NullPointerException.class,
                () -> new ShareLink(UUID.randomUUID(), null, TOKEN_HASH, CREATED_AT, EXPIRES_AT, null));
        assertThrows(NullPointerException.class,
                () -> new ShareLink(UUID.randomUUID(), UUID.randomUUID(), null, CREATED_AT, EXPIRES_AT, null));
        assertThrows(NullPointerException.class,
                () -> new ShareLink(UUID.randomUUID(), UUID.randomUUID(), TOKEN_HASH, null, EXPIRES_AT, null));
        assertThrows(NullPointerException.class,
                () -> new ShareLink(UUID.randomUUID(), UUID.randomUUID(), TOKEN_HASH, CREATED_AT, null, null));
        assertThrows(NullPointerException.class, () -> newShareLink().isActive(null));
    }

    @Test
    void toStringDoesNotExposeIdentifiersOrTokenHash() {
        ShareLink link = newShareLink();
        String representation = link.toString();

        assertFalse(representation.contains(link.id().toString()));
        assertFalse(representation.contains(link.analysisId().toString()));
        assertFalse(representation.contains(link.tokenHash()));
    }

    private static ShareLink newShareLink() {
        return new ShareLink(UUID.randomUUID(), UUID.randomUUID(), TOKEN_HASH, CREATED_AT, EXPIRES_AT, null);
    }
}
