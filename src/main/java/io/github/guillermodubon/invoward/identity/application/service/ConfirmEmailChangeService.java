package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.InvalidEmailChangeTokenException;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates the request token and delegates its atomic consumption to the transaction boundary. */
public class ConfirmEmailChangeService {

    private static final Pattern RAW_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final VerificationTokenGenerator tokenGenerator;
    private final ConfirmEmailChangeTransaction transaction;

    public ConfirmEmailChangeService(
            VerificationTokenGenerator tokenGenerator,
            ConfirmEmailChangeTransaction transaction) {
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.transaction = Objects.requireNonNull(transaction);
    }

    public void confirm(UUID authenticatedUserId, String rawToken) {
        if (authenticatedUserId == null || !isWellFormed(rawToken)) {
            throw new InvalidEmailChangeTokenException();
        }

        transaction.confirm(authenticatedUserId, tokenGenerator.hash(rawToken));
    }

    private static boolean isWellFormed(String rawToken) {
        return rawToken != null && RAW_TOKEN_PATTERN.matcher(rawToken).matches();
    }
}
