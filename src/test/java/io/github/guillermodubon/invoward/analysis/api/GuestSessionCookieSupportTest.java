package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuestSessionCookieSupportTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void readsOnlyOneCanonicalGuestUuidCookie() {
        GuestSessionCookieSupport support = support(false, "lax");
        UUID id = UUID.randomUUID();

        assertEquals(Optional.empty(), support.findGuestSessionId(new MockHttpServletRequest()));
        assertEquals(Optional.of(id), support.findGuestSessionId(requestWith(id.toString())));
        assertEquals(Optional.of(id), support.findGuestSessionId(requestWith(id.toString().toUpperCase())));
        assertEquals(Optional.empty(), support.findGuestSessionId(requestWith("not-a-uuid")));
        assertEquals(Optional.empty(), support.findGuestSessionId(requestWith("1-1-1-1-1")));
    }

    @Test
    void treatsDuplicateGuestCookiesAsAmbiguousAndAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(GuestSessionCookieSupport.COOKIE_NAME, UUID.randomUUID().toString()),
                new Cookie(GuestSessionCookieSupport.COOKIE_NAME, UUID.randomUUID().toString()));

        assertEquals(Optional.empty(), support(false, "lax").findGuestSessionId(request));
    }

    @Test
    void setsHttpOnlyScopedCookieWithSecureSameSiteAndRemainingFixedLifetime() {
        GuestSessionCookieSupport support = support(true, "strict");
        UUID id = UUID.randomUUID();
        GuestSessionOwner owner = new GuestSessionOwner(id, NOW.plusSeconds(3_602).plusMillis(100));

        ResponseCookie cookie = support.createGuestSessionCookie(owner);

        assertEquals(GuestSessionCookieSupport.COOKIE_NAME, cookie.getName());
        assertEquals(id.toString(), cookie.getValue());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Strict", cookie.getSameSite());
        assertEquals(GuestSessionCookieSupport.COOKIE_PATH, cookie.getPath());
        assertEquals(Duration.ofSeconds(3_603), cookie.getMaxAge());
    }

    @Test
    void expiredOwnerReceivesAnAlreadyExpiredCookie() {
        ResponseCookie cookie = support(false, "lax").createGuestSessionCookie(
                new GuestSessionOwner(UUID.randomUUID(), NOW.minusSeconds(1)));

        assertEquals(Duration.ZERO, cookie.getMaxAge());
        assertFalse(cookie.isSecure());
    }

    private static GuestSessionCookieSupport support(boolean secure, String sameSite) {
        return new GuestSessionCookieSupport(new GuestCookieProperties(secure, sameSite), CLOCK);
    }

    private static MockHttpServletRequest requestWith(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(GuestSessionCookieSupport.COOKIE_NAME, value));
        return request;
    }
}
