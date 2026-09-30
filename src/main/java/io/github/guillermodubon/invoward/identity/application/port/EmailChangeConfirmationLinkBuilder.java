package io.github.guillermodubon.invoward.identity.application.port;

/** Builds an email-change confirmation link for a raw, short-lived token. */
@FunctionalInterface
public interface EmailChangeConfirmationLinkBuilder {

    String build(String rawToken);
}
