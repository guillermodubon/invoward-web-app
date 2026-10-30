package io.github.guillermodubon.invoward.sharing.application.port;

import io.github.guillermodubon.invoward.sharing.application.model.GeneratedShareToken;

import java.util.Objects;
import java.util.function.Predicate;

/** Provider-neutral generation and hashing contract for public share-link bearer tokens. */
public interface ShareTokenGenerator {

    /** Initial attempt plus at most two duplicate reissues. */
    int MAX_GENERATION_ATTEMPTS = 3;

    GeneratedShareToken generate();

    /** Validates the raw token lexically before computing its SHA-256 lookup hash. */
    String hash(String rawToken);

    /**
     * Reissues tokens when their hashes already exist. The database unique constraint remains authoritative
     * for races between this check and persistence and must be handled by the creation transaction.
     */
    default GeneratedShareToken generateUnique(Predicate<String> tokenHashExists) {
        Objects.requireNonNull(tokenHashExists, "tokenHashExists must not be null");
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            GeneratedShareToken candidate = Objects.requireNonNull(generate(), "generated token must not be null");
            if (!tokenHashExists.test(candidate.tokenHash())) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique share token");
    }
}
