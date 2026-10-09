package io.github.guillermodubon.invoward.reconciliation.application.exception;

/** Signals a stale match version or a manual update that conflicts with current state. */
public final class MatchConflictException extends RuntimeException {

    public MatchConflictException() {
        super("The match changed before the update could be applied.");
    }
}
