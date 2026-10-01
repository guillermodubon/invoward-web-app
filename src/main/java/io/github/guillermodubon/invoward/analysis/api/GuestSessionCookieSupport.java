package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads and writes the opaque guest-session bearer cookie at the HTTP boundary. */
public class GuestSessionCookieSupport {

    public static final String COOKIE_NAME = "INVOWARD_GUEST";
    public static final String COOKIE_PATH = "/api/analyses";

    private final GuestCookieProperties properties;
    private final Clock clock;

    public GuestSessionCookieSupport(GuestCookieProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Returns a guest UUID only when exactly one cookie has a canonical UUID value. */
    public Optional<UUID> findGuestSessionId(HttpServletRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }

        String value = null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                if (value != null) {
                    return Optional.empty();
                }
                value = cookie.getValue();
            }
        }
        if (value == null) {
            return Optional.empty();
        }

        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** Builds the cookie with only the guest session's remaining fixed lifetime. */
    public ResponseCookie createGuestSessionCookie(GuestSessionOwner owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        Duration remaining = Duration.between(clock.instant(), owner.expiresAt());
        long remainingSeconds = remaining.isNegative() || remaining.isZero()
                ? 0
                : remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);

        return ResponseCookie.from(COOKIE_NAME, owner.guestSessionId().toString())
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(COOKIE_PATH)
                .maxAge(Duration.ofSeconds(remainingSeconds))
                .build();
    }
}
