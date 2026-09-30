package io.github.guillermodubon.invoward.identity.application.port;

import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;

/** Generates temporary token material and hashes incoming raw tokens for lookup. */
public interface VerificationTokenGenerator {

    GeneratedVerificationToken generate();

    String hash(String rawToken);
}
