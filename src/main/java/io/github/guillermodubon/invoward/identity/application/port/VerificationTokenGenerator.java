package io.github.guillermodubon.invoward.identity.application.port;

import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;

/** Generates temporary registration-token material and its persistable hash. */
public interface VerificationTokenGenerator {

    GeneratedVerificationToken generate();
}
