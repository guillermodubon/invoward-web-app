package io.github.guillermodubon.invoward.identity.application.model;

import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Provider-neutral persisted verification-token data. Sensitive values are redacted in logs. */
public record EmailVerificationToken(
        UUID userId,
        String tokenHash,
        EmailVerificationPurpose purpose,
        String targetEmail,
        Instant expiresAt,
        Instant usedAt,
        Instant createdAt) {

    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public EmailVerificationToken {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(targetEmail, "targetEmail must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!TOKEN_HASH_PATTERN.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException("tokenHash must be a lowercase SHA-256 hexadecimal value");
        }
        if (targetEmail.isBlank()) {
            throw new IllegalArgumentException("targetEmail must not be blank");
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
        return "EmailVerificationToken[userId=" + userId
                + ", tokenHash=[REDACTED], purpose=" + purpose
                + ", targetEmail=[REDACTED], expiresAt=" + expiresAt
                + ", usedAt=" + usedAt + ", createdAt=" + createdAt + "]";
    }
}
