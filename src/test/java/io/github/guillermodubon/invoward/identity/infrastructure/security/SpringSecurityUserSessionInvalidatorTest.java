package io.github.guillermodubon.invoward.identity.infrastructure.security;

import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringSecurityUserSessionInvalidatorTest {

    private static final UUID USER_ID = UUID.fromString("ad3b7bdd-2793-41ee-86b2-6c50e40b8d1a");
    private static final UUID OTHER_USER_ID = UUID.fromString("b454c621-53e6-4a95-a440-2d39550df0fc");

    @Test
    void expiresEveryActiveSessionForTheRequestedUserOnly() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        AuthenticatedUserPrincipal firstLogin = principal(USER_ID);
        AuthenticatedUserPrincipal secondLogin = principal(USER_ID);
        AuthenticatedUserPrincipal otherUser = principal(OTHER_USER_ID);
        registry.registerNewSession("session-one", firstLogin);
        registry.registerNewSession("session-two", secondLogin);
        registry.registerNewSession("other-user-session", otherUser);

        new SpringSecurityUserSessionInvalidator(registry).invalidateAll(USER_ID);

        assertTrue(registry.getSessionInformation("session-one").isExpired());
        assertTrue(registry.getSessionInformation("session-two").isExpired());
        assertFalse(registry.getSessionInformation("other-user-session").isExpired());
    }

    @Test
    void expiredSessionStrategyReturnsSafeJsonWithoutSessionDetails() throws Exception {
        String sensitiveSessionId = "private-session-id";
        SessionInformation information = new SessionInformation(
                principal(USER_ID), sensitiveSessionId, new Date());
        MockHttpServletResponse response = new MockHttpServletResponse();
        JsonSessionInformationExpiredStrategy strategy =
                new JsonSessionInformationExpiredStrategy(new ObjectMapper());

        strategy.onExpiredSessionDetected(new org.springframework.security.web.session.SessionInformationExpiredEvent(
                information, new MockHttpServletRequest(), response));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("SESSION_INVALIDATED"));
        assertTrue(body.contains("Your session is no longer valid. Please sign in again."));
        assertFalse(body.contains(sensitiveSessionId));
    }

    private static AuthenticatedUserPrincipal principal(UUID userId) {
        return new AuthenticatedUserPrincipal(new UserAccount(
                userId,
                "Session User",
                userId + "@example.com",
                "$argon2id$test-hash",
                true,
                UserStatus.ACTIVE,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")));
    }
}
