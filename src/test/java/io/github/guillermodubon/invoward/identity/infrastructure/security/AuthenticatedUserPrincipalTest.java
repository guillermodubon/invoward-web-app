package io.github.guillermodubon.invoward.identity.infrastructure.security;

import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatedUserPrincipalTest {

    private static final String PASSWORD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$secret-hash";

    @Test
    void activeVerifiedAccountIsEnabledWithoutInventedAuthorities() {
        AuthenticatedUserPrincipal principal = principal(UserStatus.ACTIVE, true);

        assertTrue(principal.isEnabled());
        assertEquals("person@example.com", principal.getUsername());
        assertEquals(PASSWORD_HASH, principal.getPassword());
        assertEquals("Person", principal.displayName());
        assertEquals(UserStatus.ACTIVE, principal.status());
        assertTrue(principal.getAuthorities().isEmpty());
        assertTrue(principal.isAccountNonExpired());
        assertTrue(principal.isAccountNonLocked());
        assertTrue(principal.isCredentialsNonExpired());
    }

    @Test
    void pendingAccountIsDisabled() {
        assertFalse(principal(UserStatus.PENDING_VERIFICATION, false).isEnabled());
    }

    @Test
    void disabledAccountIsDisabled() {
        assertFalse(principal(UserStatus.DISABLED, true).isEnabled());
    }

    @Test
    void activeButUnverifiedAccountIsDisabled() {
        assertFalse(principal(UserStatus.ACTIVE, false).isEnabled());
    }

    @Test
    void stringRepresentationNeverContainsPasswordHash() {
        AuthenticatedUserPrincipal principal = principal(UserStatus.ACTIVE, true);

        assertFalse(principal.toString().contains(PASSWORD_HASH));
        assertTrue(principal.toString().contains("REDACTED"));
    }

    private static AuthenticatedUserPrincipal principal(UserStatus status, boolean verified) {
        UserAccount account = new UserAccount(
                UUID.fromString("a3d9151b-50d3-42c4-9c08-9637795af0c1"),
                "Person",
                "Person@Example.com",
                PASSWORD_HASH,
                verified,
                status,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
        return new AuthenticatedUserPrincipal(account);
    }
}
