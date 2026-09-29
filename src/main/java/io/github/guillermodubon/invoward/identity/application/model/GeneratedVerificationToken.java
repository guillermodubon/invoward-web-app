package io.github.guillermodubon.invoward.identity.application.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Short-lived token material; both values are sensitive and must never be logged. */
public record GeneratedVerificationToken(String rawToken, String tokenHash) {

    private static final Pattern RAW_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public GeneratedVerificationToken {
        Objects.requireNonNull(rawToken, "rawToken must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        if (!RAW_TOKEN_PATTERN.matcher(rawToken).matches()) {
            throw new IllegalArgumentException("rawToken must be an unpadded 32-byte Base64 URL-safe token");
        }
        if (!TOKEN_HASH_PATTERN.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException("tokenHash must be a lowercase SHA-256 hexadecimal value");
        }
    }

    @Override
    public String toString() {
        return "GeneratedVerificationToken[rawToken=[REDACTED], tokenHash=[REDACTED]]";
    }
}
