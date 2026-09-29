package io.github.guillermodubon.invoward.identity.application.port;

/** Builds a public registration verification link for a raw, short-lived token. */
@FunctionalInterface
public interface RegistrationVerificationLinkBuilder {

    String build(String rawVerificationToken);
}
