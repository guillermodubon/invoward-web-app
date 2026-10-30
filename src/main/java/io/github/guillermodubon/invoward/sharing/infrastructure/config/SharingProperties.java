package io.github.guillermodubon.invoward.sharing.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Trusted server configuration for share-link URLs and per-Analysis link limits. */
@ConfigurationProperties(prefix = "invoward.sharing")
public record SharingProperties(
        @DefaultValue("http://localhost:8080") URI publicBaseUrl,
        @DefaultValue("10") int maxActiveLinksPerAnalysis) {

    private static final int MAX_ACTIVE_LINKS_PER_ANALYSIS = 10;
    private static final Pattern SAFE_PATH_SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+");

    public SharingProperties {
        publicBaseUrl = validateAndNormalize(publicBaseUrl);
        if (maxActiveLinksPerAnalysis < 1 || maxActiveLinksPerAnalysis > MAX_ACTIVE_LINKS_PER_ANALYSIS) {
            throw new IllegalArgumentException(
                    "invoward.sharing.max-active-links-per-analysis must be between 1 and 10");
        }
    }

    void validateForProfiles(String... profiles) {
        Objects.requireNonNull(profiles, "profiles must not be null");
        boolean production = Arrays.stream(profiles)
                .filter(Objects::nonNull)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
        if (production && !"https".equals(publicBaseUrl.getScheme())) {
            throw new IllegalArgumentException(
                    "invoward.sharing.public-base-url must use HTTPS in production");
        }
    }

    private static URI validateAndNormalize(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalidPublicBaseUrl();
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalidPublicBaseUrl();
        }

        String asciiUrl = uri.toASCIIString();
        if (asciiUrl.indexOf('%') >= 0 || asciiUrl.indexOf('\\') >= 0
                || asciiUrl.chars().anyMatch(character -> Character.isWhitespace(character)
                        || Character.isISOControl(character))) {
            throw invalidPublicBaseUrl();
        }

        validatePath(uri.getRawPath());
        if (scheme.equals("http") && !isLoopbackHost(uri.getHost())) {
            throw new IllegalArgumentException(
                    "invoward.sharing.public-base-url may use HTTP only for localhost development");
        }

        String normalizedUrl = scheme + asciiUrl.substring(uri.getScheme().length());
        String path = uri.getRawPath();
        if (path != null && path.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        try {
            return URI.create(normalizedUrl);
        } catch (IllegalArgumentException exception) {
            throw invalidPublicBaseUrl();
        }
    }

    private static void validatePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.equals("/")) {
            return;
        }
        if (!rawPath.startsWith("/") || rawPath.contains("//")) {
            throw invalidPublicBaseUrl();
        }

        String path = rawPath.substring(1);
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || !SAFE_PATH_SEGMENT.matcher(segment).matches()) {
                throw invalidPublicBaseUrl();
            }
        }
    }

    private static boolean isLoopbackHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        return normalizedHost.equals("localhost")
                || normalizedHost.equals("127.0.0.1")
                || normalizedHost.equals("::1");
    }

    private static IllegalArgumentException invalidPublicBaseUrl() {
        return new IllegalArgumentException(
                "invoward.sharing.public-base-url must be an absolute HTTP(S) base URI without credentials, "
                        + "query, fragment, encoded or traversal path segments");
    }
}
