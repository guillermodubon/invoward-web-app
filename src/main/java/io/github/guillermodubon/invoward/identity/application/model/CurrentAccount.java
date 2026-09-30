package io.github.guillermodubon.invoward.identity.application.model;

import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;

import java.util.Objects;
import java.util.UUID;

/** Safe, freshly loaded projection used to build the authenticated account response. */
public record CurrentAccount(
        UUID userId,
        String displayName,
        String email,
        boolean emailVerified,
        UserStatus status) implements AuthenticatedIdentity {

    public CurrentAccount {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(email);
        Objects.requireNonNull(status);
    }

    public static CurrentAccount from(UserAccount account) {
        Objects.requireNonNull(account);
        return new CurrentAccount(
                account.id(), account.displayName(), account.email(), account.emailVerified(), account.status());
    }
}
