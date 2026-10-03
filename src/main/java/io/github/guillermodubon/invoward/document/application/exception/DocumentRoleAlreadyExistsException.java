package io.github.guillermodubon.invoward.document.application.exception;

/** Safe conflict raised when an Analysis already contains a document for the requested role. */
public final class DocumentRoleAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentRoleAlreadyExistsException() {
        super("A document for this role already exists in the Analysis.");
    }
}
