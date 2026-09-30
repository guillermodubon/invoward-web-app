package io.github.guillermodubon.invoward.identity.infrastructure.security;

import io.github.guillermodubon.invoward.identity.application.port.UserSessionInvalidator;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** Expires registered Spring Security sessions without exposing session IDs to application code. */
@Component
public final class SpringSecurityUserSessionInvalidator implements UserSessionInvalidator {

    private final SessionRegistry sessionRegistry;

    public SpringSecurityUserSessionInvalidator(SessionRegistry sessionRegistry) {
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry);
    }

    @Override
    public void invalidateAll(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof AuthenticatedUserPrincipal authenticatedPrincipal
                    && userId.equals(authenticatedPrincipal.userId())) {
                expireActiveSessions(principal);
            }
        }
    }

    private void expireActiveSessions(Object principal) {
        for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
            session.expireNow();
        }
    }
}
