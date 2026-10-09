package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that an owner-visible Analysis does not yet have a persisted match set. */
public final class MatchesNotFoundException extends RuntimeException {

    public MatchesNotFoundException() {
        super("Matches were not found.");
    }
}
