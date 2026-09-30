package io.github.guillermodubon.invoward.identity.application.port;

import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations for single-use password-reset tokens. */
public interface PasswordResetTokenRepository {

    void save(PasswordResetToken token);

    Optional<PasswordResetToken> findByTokenHashForUpdate(String tokenHash);

    Optional<Instant> findLatestCreatedAtByUser(UUID userId);

    boolean markUsed(String tokenHash, Instant usedAt);

    int invalidateUnusedByUser(UUID userId, Instant invalidatedAt);
}
