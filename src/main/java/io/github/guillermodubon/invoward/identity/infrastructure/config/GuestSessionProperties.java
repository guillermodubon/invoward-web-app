package io.github.guillermodubon.invoward.identity.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "invoward.guest")
public record GuestSessionProperties(@DefaultValue("24h") Duration sessionTtl) {

    private static final Duration MAX_SESSION_TTL = Duration.ofHours(24);

    public GuestSessionProperties {
        if (sessionTtl == null
                || sessionTtl.isZero()
                || sessionTtl.isNegative()
                || sessionTtl.compareTo(MAX_SESSION_TTL) > 0) {
            throw new IllegalArgumentException(
                    "invoward.guest.session-ttl must be greater than 0 and at most 24h");
        }
    }
}
