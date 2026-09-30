package io.github.guillermodubon.invoward.identity.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Provider-neutral persisted password-reset token data. The token hash is sensitive. */
public record PasswordResetToken(
        UUID userId,
        String tokenHash,
        Instant expiresAt,
        Instant usedAt,
        Instant createdAt) {

    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public PasswordResetToken {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!TOKEN_HASH_PATTERN.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException("tokenHash must be a lowercase SHA-256 hexadecimal value");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (usedAt != null && usedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("usedAt must not be before createdAt");
        }
    }

    @Override
    public String toString() {
        return "PasswordResetToken[userId=" + userId
                + ", tokenHash=[REDACTED], expiresAt=" + expiresAt
                + ", usedAt=" + usedAt + ", createdAt=" + createdAt + "]";
    }
}
