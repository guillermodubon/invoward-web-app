package io.github.guillermodubon.invoward.identity.application.port;

import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations for registration and email-change verification tokens. */
public interface EmailVerificationTokenRepository {

    void save(EmailVerificationToken token);

    Optional<EmailVerificationToken> findByTokenHashForUpdate(String tokenHash);

    Optional<Instant> findLatestCreatedAtByUserAndPurpose(UUID userId, EmailVerificationPurpose purpose);

    boolean markUsed(String tokenHash, Instant usedAt);

    int invalidateUnusedByUserAndPurpose(
            UUID userId,
            EmailVerificationPurpose purpose,
            Instant invalidatedAt);
}
