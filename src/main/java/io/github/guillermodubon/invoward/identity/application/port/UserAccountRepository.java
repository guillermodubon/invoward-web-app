package io.github.guillermodubon.invoward.identity.application.port;

import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;

import java.util.Optional;

/** Persistence operations required by identity application use cases. */
public interface UserAccountRepository {

    Optional<UserAccount> findByNormalizedEmail(String normalizedEmail);

    boolean existsByNormalizedEmail(String normalizedEmail);

    UserAccount create(NewUserAccount newUserAccount);
}
