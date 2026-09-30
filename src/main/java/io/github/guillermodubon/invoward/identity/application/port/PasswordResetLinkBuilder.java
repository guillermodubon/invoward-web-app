package io.github.guillermodubon.invoward.identity.application.port;

/** Builds a public password-reset link for a raw, short-lived token. */
@FunctionalInterface
public interface PasswordResetLinkBuilder {

    String build(String rawToken);
}
