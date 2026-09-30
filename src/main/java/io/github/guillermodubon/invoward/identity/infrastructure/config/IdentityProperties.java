package io.github.guillermodubon.invoward.identity.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "invoward.identity")
public record IdentityProperties(
        Duration registrationVerificationTokenTtl,
        String verificationUrl,
        Duration verificationResendCooldown,
        Duration passwordResetTokenTtl,
        Duration passwordResetRequestCooldown,
        String passwordResetUrl,
        Duration emailChangeTokenTtl,
        Duration emailChangeRequestCooldown,
        String emailChangeConfirmationUrl) {

    private static final Duration MIN_RESET_TOKEN_TTL = Duration.ofMinutes(10);
    private static final Duration MAX_RESET_TOKEN_TTL = Duration.ofMinutes(60);
    private static final Duration MAX_LONG_LIVED_TOKEN_TTL = Duration.ofHours(72);
    private static final Duration MIN_COOLDOWN = Duration.ofSeconds(30);
    private static final Duration MAX_COOLDOWN = Duration.ofMinutes(10);

    public IdentityProperties {
        validatePositiveTtl(
                registrationVerificationTokenTtl,
                "invoward.identity.registration-verification-token-ttl",
                MAX_LONG_LIVED_TOKEN_TTL);
        validateCooldown(verificationResendCooldown, "invoward.identity.verification-resend-cooldown");
        validateResetTtl(passwordResetTokenTtl);
        validateCooldown(passwordResetRequestCooldown, "invoward.identity.password-reset-request-cooldown");
        validatePositiveTtl(
                emailChangeTokenTtl,
                "invoward.identity.email-change-token-ttl",
                MAX_LONG_LIVED_TOKEN_TTL);
        validateCooldown(emailChangeRequestCooldown, "invoward.identity.email-change-request-cooldown");
        validateUrl(verificationUrl, "invoward.identity.verification-url");
        validateUrl(passwordResetUrl, "invoward.identity.password-reset-url");
        validateUrl(emailChangeConfirmationUrl, "invoward.identity.email-change-confirmation-url");
    }

    public String verificationUrlWithToken(String rawToken) {
        return appendToken(verificationUrl, "invoward.identity.verification-url", rawToken);
    }

    public String passwordResetUrlWithToken(String rawToken) {
        return appendToken(passwordResetUrl, "invoward.identity.password-reset-url", rawToken);
    }

    public String emailChangeConfirmationUrlWithToken(String rawToken) {
        return appendToken(
                emailChangeConfirmationUrl,
                "invoward.identity.email-change-confirmation-url",
                rawToken);
    }

    @Override
    public String toString() {
        return "IdentityProperties[registrationVerificationTokenTtl="
                + registrationVerificationTokenTtl
                + ", verificationUrl=[REDACTED]"
                + ", verificationResendCooldown=" + verificationResendCooldown
                + ", passwordResetTokenTtl=" + passwordResetTokenTtl
                + ", passwordResetRequestCooldown=" + passwordResetRequestCooldown
                + ", passwordResetUrl=[REDACTED]"
                + ", emailChangeTokenTtl=" + emailChangeTokenTtl
                + ", emailChangeRequestCooldown=" + emailChangeRequestCooldown
                + ", emailChangeConfirmationUrl=[REDACTED]]";
    }

    private static void validatePositiveTtl(Duration ttl, String propertyName, Duration maximum) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than 0 and at most " + maximum);
        }
    }

    private static void validateResetTtl(Duration ttl) {
        if (ttl == null || ttl.compareTo(MIN_RESET_TOKEN_TTL) < 0 || ttl.compareTo(MAX_RESET_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException(
                    "invoward.identity.password-reset-token-ttl must be between 10m and 60m");
        }
    }

    private static void validateCooldown(Duration cooldown, String propertyName) {
        if (cooldown == null || cooldown.compareTo(MIN_COOLDOWN) < 0 || cooldown.compareTo(MAX_COOLDOWN) > 0) {
            throw new IllegalArgumentException(propertyName + " must be between 30s and 10m");
        }
    }

    private static void validateUrl(String value, String propertyName) {
        URI uri = parseUri(value, propertyName);
        String scheme = uri.getScheme();
        if (!uri.isAbsolute()
                || uri.isOpaque()
                || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || containsTokenParameter(uri.getRawQuery())) {
            throw new IllegalArgumentException(propertyName + " must be an absolute HTTP(S) URL "
                    + "with a host and without user-info, a fragment, or a token query parameter");
        }
    }

    private static URI parseUri(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must be configured");
        }
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(propertyName + " is not a valid URI");
        }
    }

    private static String appendToken(String baseUrl, String propertyName, String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken must not be null");
        if (rawToken.isEmpty()) {
            throw new IllegalArgumentException("rawToken must not be empty");
        }

        URI baseUri = parseUri(baseUrl, propertyName);
        String encodedToken = URLEncoder.encode(rawToken, StandardCharsets.UTF_8).replace("+", "%20");
        String base = baseUri.toASCIIString();
        String rawQuery = baseUri.getRawQuery();

        if (rawQuery == null || rawQuery.isEmpty()) {
            if (!base.endsWith("?")) {
                base += "?";
            }
        } else if (!rawQuery.endsWith("&")) {
            base += "&";
        }

        return base + "token=" + encodedToken;
    }

    private static boolean containsTokenParameter(String rawQuery) {
        if (rawQuery == null) {
            return false;
        }
        for (String parameter : rawQuery.split("&", -1)) {
            int separator = parameter.indexOf('=');
            String encodedName = separator < 0 ? parameter : parameter.substring(0, separator);
            String name = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
            if ("token".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
