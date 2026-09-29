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
public record IdentityProperties(Duration registrationVerificationTokenTtl, String verificationUrl) {

    private static final Duration MAX_VERIFICATION_TOKEN_TTL = Duration.ofHours(72);

    public IdentityProperties {
        validateTtl(registrationVerificationTokenTtl);
        validateVerificationUrl(verificationUrl);
    }

    public String verificationUrlWithToken(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken must not be null");
        if (rawToken.isEmpty()) {
            throw new IllegalArgumentException("rawToken must not be empty");
        }

        URI baseUri = parseVerificationUri(verificationUrl);
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

    @Override
    public String toString() {
        return "IdentityProperties[registrationVerificationTokenTtl="
                + registrationVerificationTokenTtl + ", verificationUrl=[REDACTED]]";
    }

    private static void validateTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_VERIFICATION_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException(
                    "invoward.identity.registration-verification-token-ttl must be greater than 0 and at most 72h");
        }
    }

    private static void validateVerificationUrl(String value) {
        URI uri = parseVerificationUri(value);
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
            throw new IllegalArgumentException("invoward.identity.verification-url must be an absolute HTTP(S) URL "
                    + "with a host and without user-info, a fragment, or a token query parameter");
        }
    }

    private static URI parseVerificationUri(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("invoward.identity.verification-url must be configured");
        }
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invoward.identity.verification-url is not a valid URI");
        }
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
