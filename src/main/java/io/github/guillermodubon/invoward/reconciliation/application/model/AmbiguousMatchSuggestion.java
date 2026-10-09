package io.github.guillermodubon.invoward.reconciliation.application.model;

import java.util.Objects;

/** Untrusted provider key that must only be resolved against the request's ephemeral candidate labels. */
public record AmbiguousMatchSuggestion(String candidateKey) {

    public static final String NO_MATCH = "NO_MATCH";

    public AmbiguousMatchSuggestion {
        Objects.requireNonNull(candidateKey, "candidateKey must not be null");
    }

    public boolean isNoMatch() {
        return NO_MATCH.equals(candidateKey);
    }

    @Override
    public String toString() {
        return "AmbiguousMatchSuggestion[candidateKeyPresent=" + !candidateKey.isBlank() + "]";
    }
}
