package io.github.guillermodubon.invoward.analysis.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Locale;
import java.util.Objects;

/** HTTP cookie policy for anonymous Analysis ownership. */
@ConfigurationProperties(prefix = "invoward.guest.cookie")
public record GuestCookieProperties(
        @DefaultValue("false") boolean secure,
        @DefaultValue("lax") String sameSite) {

    public GuestCookieProperties {
        Objects.requireNonNull(sameSite, "invoward.guest.cookie.same-site must not be null");
        sameSite = switch (sameSite.toLowerCase(Locale.ROOT)) {
            case "lax" -> "Lax";
            case "strict" -> "Strict";
            case "none" -> "None";
            default -> throw new IllegalArgumentException(
                    "invoward.guest.cookie.same-site must be Lax, Strict, or None");
        };
        if ("None".equals(sameSite) && !secure) {
            throw new IllegalArgumentException(
                    "invoward.guest.cookie.secure must be true when same-site is None");
        }
    }
}
