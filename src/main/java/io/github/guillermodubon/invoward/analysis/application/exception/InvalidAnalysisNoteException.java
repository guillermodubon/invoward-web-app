package io.github.guillermodubon.invoward.analysis.application.exception;

/** Safe signal that an Analysis note request does not satisfy the note contract. */
public final class InvalidAnalysisNoteException extends RuntimeException {

    public InvalidAnalysisNoteException() {
        super("The analysis note is invalid.");
    }
}
