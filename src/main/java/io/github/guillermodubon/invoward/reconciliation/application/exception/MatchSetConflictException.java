package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals that persisted match rows are partial or inconsistent with the confirmed extraction set. */
public final class MatchSetConflictException extends RuntimeException {

    public MatchSetConflictException() {
        super("The persisted match set conflicts with the confirmed extraction.");
    }
}
