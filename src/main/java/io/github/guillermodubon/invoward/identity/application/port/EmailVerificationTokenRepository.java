package io.github.guillermodubon.invoward.identity.application.port;

import java.time.Instant;
import java.util.UUID;

/** Persistence operation for the initial registration-verification token only. */
public interface EmailVerificationTokenRepository {

    void saveRegistrationToken(
            UUID userId,
            String normalizedTargetEmail,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt);
}
