package io.github.guillermodubon.invoward.identity.application.model;

import io.github.guillermodubon.invoward.identity.domain.UserAccount;

import java.time.Instant;
import java.util.Objects;

/** Validated persistence input containing an already-hashed password and normalized identity data. */
public record NewUserAccount(String displayName, String email, String passwordHash, Instant createdAt) {

    public NewUserAccount {
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(email, "email must not be null");
        if (!displayName.equals(UserAccount.normalizeDisplayName(displayName))) {
            throw new IllegalArgumentException("displayName must already be normalized");
        }
        if (!email.equals(UserAccount.normalizeEmail(email))) {
            throw new IllegalArgumentException("email must already be normalized");
        }
        if (passwordHash == null || passwordHash.isBlank() || passwordHash.length() > 255) {
            throw new IllegalArgumentException("passwordHash is invalid");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    @Override
    public String toString() {
        return "NewUserAccount[passwordHash=[REDACTED]]";
    }
}
