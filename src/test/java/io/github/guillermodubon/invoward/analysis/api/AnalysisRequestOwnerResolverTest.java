package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import io.github.guillermodubon.invoward.identity.application.service.GuestSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisRequestOwnerResolverTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private final GuestSessionService guestSessionService = mock(GuestSessionService.class);
    private final GuestSessionCookieSupport cookieSupport = new GuestSessionCookieSupport(
            new GuestCookieProperties(false, "lax"),
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    private final AnalysisRequestOwnerResolver resolver = new AnalysisRequestOwnerResolver(
            cookieSupport, guestSessionService);

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(guestSessionService);
    }

    @Test
    void authenticatedIdentityWinsAndGuestCookieIsIgnoredForCreateAndRead() {
        UUID userId = UUID.randomUUID();
        UUID guestId = UUID.randomUUID();
        AuthenticatedIdentity identity = mock(AuthenticatedIdentity.class);
        when(identity.userId()).thenReturn(userId);
        MockHttpServletRequest request = requestWithGuestCookie(guestId);

        AnalysisOwner createdOwner = resolver.resolveForCreate(identity, request);
        Optional<AnalysisOwner> readOwner = resolver.resolveForRead(identity, request);

        assertEquals(new RegisteredUserOwner(userId), createdOwner);
        assertEquals(Optional.of(new RegisteredUserOwner(userId)), readOwner);
        verify(guestSessionService, never()).findActive(guestId);
        verify(guestSessionService, never()).create();
    }

    @Test
    void anonymousCreateReusesAnActiveCookieSession() {
        GuestSession session = activeSession();
        when(guestSessionService.findActive(session.id())).thenReturn(Optional.of(session));

        AnalysisOwner owner = resolver.resolveForCreate(null, requestWithGuestCookie(session.id()));

        assertEquals(new GuestSessionOwner(session.id(), session.expiresAt()), owner);
        verify(guestSessionService, never()).create();
    }

    @Test
    void anonymousCreateCreatesSessionWhenCookieIsMissingMalformedOrUnknown() {
        GuestSession createdSession = activeSession();
        when(guestSessionService.create()).thenReturn(createdSession);

        AnalysisOwner missingCookieOwner = resolver.resolveForCreate(null, new MockHttpServletRequest());
        AnalysisOwner malformedCookieOwner = resolver.resolveForCreate(
                null, requestWithGuestCookieValue("malformed"));
        UUID unknownId = UUID.randomUUID();
        when(guestSessionService.findActive(unknownId)).thenReturn(Optional.empty());
        AnalysisOwner unknownCookieOwner = resolver.resolveForCreate(null, requestWithGuestCookie(unknownId));

        assertEquals(new GuestSessionOwner(createdSession.id(), createdSession.expiresAt()), missingCookieOwner);
        assertEquals(missingCookieOwner, malformedCookieOwner);
        assertEquals(missingCookieOwner, unknownCookieOwner);
        verify(guestSessionService, org.mockito.Mockito.times(3)).create();
    }

    @Test
    void anonymousReadResolvesOnlyAnActiveCookieSessionAndNeverCreates() {
        GuestSession session = activeSession();
        when(guestSessionService.findActive(session.id())).thenReturn(Optional.of(session));
        UUID expiredOrUnknownId = UUID.randomUUID();
        when(guestSessionService.findActive(expiredOrUnknownId)).thenReturn(Optional.empty());

        Optional<AnalysisOwner> activeOwner = resolver.resolveForRead(null, requestWithGuestCookie(session.id()));
        Optional<AnalysisOwner> missingCookieOwner = resolver.resolveForRead(null, new MockHttpServletRequest());
        Optional<AnalysisOwner> malformedCookieOwner = resolver.resolveForRead(
                null, requestWithGuestCookieValue("malformed"));
        Optional<AnalysisOwner> unknownCookieOwner = resolver.resolveForRead(
                null, requestWithGuestCookie(expiredOrUnknownId));

        assertEquals(Optional.of(new GuestSessionOwner(session.id(), session.expiresAt())), activeOwner);
        assertTrue(missingCookieOwner.isEmpty());
        assertTrue(malformedCookieOwner.isEmpty());
        assertTrue(unknownCookieOwner.isEmpty());
        verify(guestSessionService, never()).create();
    }

    @Test
    void returnedGuestOwnerNeverContainsPersistenceObjects() {
        GuestSession session = activeSession();
        when(guestSessionService.create()).thenReturn(session);

        AnalysisOwner owner = resolver.resolveForCreate(null, new MockHttpServletRequest());

        assertInstanceOf(GuestSessionOwner.class, owner);
    }

    @Test
    void recordsSuccessfulActivityOnlyForGuestOwners() {
        UUID userId = UUID.randomUUID();
        UUID guestId = UUID.randomUUID();

        resolver.recordSuccessfulActivity(new RegisteredUserOwner(userId));
        resolver.recordSuccessfulActivity(new GuestSessionOwner(guestId, NOW.plus(Duration.ofHours(24))));

        verify(guestSessionService).touch(guestId);
        verify(guestSessionService, never()).touch(userId);
    }

    private static GuestSession activeSession() {
        return GuestSession.create(UUID.randomUUID(), NOW, Duration.ofHours(24));
    }

    private static MockHttpServletRequest requestWithGuestCookie(UUID id) {
        return requestWithGuestCookieValue(id.toString());
    }

    private static MockHttpServletRequest requestWithGuestCookieValue(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(GuestSessionCookieSupport.COOKIE_NAME, value));
        return request;
    }
}
