package io.github.guillermodubon.invoward.identity.application.port;

import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;

import java.util.Optional;
import java.util.UUID;

/** Persistence operations required by identity application use cases. */
public interface UserAccountRepository {

    Optional<UserAccount> findById(UUID userId);

    Optional<UserAccount> findByNormalizedEmail(String normalizedEmail);

    boolean existsByNormalizedEmail(String normalizedEmail);

    UserAccount create(NewUserAccount newUserAccount);

    UserAccount activateVerifiedRegistration(UserAccount activatedAccount);

    UserAccount updateDisplayName(UserAccount updatedAccount);

    UserAccount updatePasswordHash(UserAccount updatedAccount);

    UserAccount updateEmail(UserAccount updatedAccount);
}
