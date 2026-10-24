package io.github.guillermodubon.invoward.analysis.application.exception;

/** Safe signal that an Analysis note changed since the caller last read it. */
public final class NoteVersionConflictException extends RuntimeException {

    public NoteVersionConflictException() {
        super("The analysis note was modified concurrently.");
    }
}
