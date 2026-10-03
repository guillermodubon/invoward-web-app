package io.github.guillermodubon.invoward.document.application.exception;

/** Safe, typed rejection of untrusted upload metadata or bytes. */
public final class DocumentUploadValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Failure failure;

    public DocumentUploadValidationException(Failure failure) {
        super(messageFor(failure));
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }

    private static String messageFor(Failure failure) {
        return switch (failure) {
            case EMPTY_FILE -> "Document must not be empty";
            case TOO_LARGE -> "Document exceeds the configured size limit";
            case UNSUPPORTED_TYPE -> "Document type is not supported";
            case TYPE_MISMATCH -> "Document extension and content type do not match";
            case INVALID_FILENAME -> "Document filename is invalid";
            case DOCUMENT_INVALID -> "Document content is invalid";
            case PDF_PASSWORD_PROTECTED -> "Password-protected PDF documents are not supported";
            case PDF_PAGE_LIMIT_EXCEEDED -> "PDF document exceeds the configured page limit";
            case IMAGE_DIMENSIONS_EXCEEDED -> "Image exceeds the configured dimensions";
        };
    }

    public enum Failure {
        EMPTY_FILE,
        TOO_LARGE,
        UNSUPPORTED_TYPE,
        TYPE_MISMATCH,
        INVALID_FILENAME,
        DOCUMENT_INVALID,
        PDF_PASSWORD_PROTECTED,
        PDF_PAGE_LIMIT_EXCEEDED,
        IMAGE_DIMENSIONS_EXCEEDED
    }
}
