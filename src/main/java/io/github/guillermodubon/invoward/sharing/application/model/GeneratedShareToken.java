package io.github.guillermodubon.invoward.sharing.application.model;

import java.util.Base64;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** Temporary bearer-token material. Its string representation never reveals either value. */
public record GeneratedShareToken(String rawToken, String tokenHash) {

    private static final Pattern RAW_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public GeneratedShareToken {
        Objects.requireNonNull(rawToken, "rawToken must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        if (!isCanonicalRawToken(rawToken)) {
            throw new IllegalArgumentException("rawToken must be a canonical 32-byte Base64 URL-safe token");
        }
        if (!TOKEN_HASH_PATTERN.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException("tokenHash must be a lowercase SHA-256 hexadecimal value");
        }
    }

    /** Validates the strict public-token alphabet and canonical encoding without hashing or lookup. */
    public static boolean isCanonicalRawToken(String rawToken) {
        if (rawToken == null || rawToken.length() != 43 || !RAW_TOKEN_PATTERN.matcher(rawToken).matches()) {
            return false;
        }
        byte[] decoded = null;
        try {
            decoded = Base64.getUrlDecoder().decode(rawToken);
            return decoded.length == 32
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(rawToken);
        } catch (IllegalArgumentException exception) {
            return false;
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    @Override
    public String toString() {
        return "GeneratedShareToken[rawToken=[REDACTED], tokenHash=[REDACTED]]";
    }
}
