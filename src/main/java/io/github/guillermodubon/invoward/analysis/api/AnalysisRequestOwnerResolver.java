package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import io.github.guillermodubon.invoward.identity.application.service.GuestSessionService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;
import java.util.Optional;

/** Resolves authenticated or guest ownership and records successful guest activity. */
public class AnalysisRequestOwnerResolver {

    private final GuestSessionCookieSupport cookieSupport;
    private final GuestSessionService guestSessionService;

    public AnalysisRequestOwnerResolver(
            GuestSessionCookieSupport cookieSupport,
            GuestSessionService guestSessionService) {
        this.cookieSupport = Objects.requireNonNull(cookieSupport);
        this.guestSessionService = Objects.requireNonNull(guestSessionService);
    }

    /** Resolves a POST owner, creating a guest session only when no active cookie resolves. */
    public AnalysisOwner resolveForCreate(
            AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (authenticatedIdentity != null) {
            return new RegisteredUserOwner(authenticatedIdentity.userId());
        }
        return resolveActiveGuestSession(request)
                .map(AnalysisRequestOwnerResolver::toOwner)
                .orElseGet(() -> toOwner(guestSessionService.create()));
    }

    /** Resolves a read owner without creating or extending any guest session. */
    public Optional<AnalysisOwner> resolveForRead(
            AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (authenticatedIdentity != null) {
            return Optional.of(new RegisteredUserOwner(authenticatedIdentity.userId()));
        }
        return resolveActiveGuestSession(request).map(AnalysisRequestOwnerResolver::toOwner);
    }

    /** Touches a guest session only after its requested Analysis operation has succeeded. */
    public void recordSuccessfulActivity(AnalysisOwner owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        if (owner instanceof GuestSessionOwner guestOwner) {
            guestSessionService.touch(guestOwner.guestSessionId());
        }
    }

    private Optional<GuestSession> resolveActiveGuestSession(HttpServletRequest request) {
        return cookieSupport.findGuestSessionId(request).flatMap(guestSessionService::findActive);
    }

    private static GuestSessionOwner toOwner(GuestSession guestSession) {
        return new GuestSessionOwner(guestSession.id(), guestSession.expiresAt());
    }
}
