package io.github.guillermodubon.invoward.document.api;

import io.github.guillermodubon.invoward.document.api.model.DocumentErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** Handles multipart parsing failures which can occur before Spring resolves a controller method. */
@RestControllerAdvice
public class DocumentMultipartExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<DocumentErrorResponse> handleSizeExceeded(MaxUploadSizeExceededException exception) {
        return tooLarge();
    }

    private static ResponseEntity<DocumentErrorResponse> tooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new DocumentErrorResponse(
                        "DOCUMENT_TOO_LARGE", "Document exceeds the configured size limit."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<DocumentErrorResponse> handleMultipartFailure(MultipartException exception) {
        if (hasCause(exception, MaxUploadSizeExceededException.class)) {
            return tooLarge();
        }
        return ResponseEntity.badRequest()
                .body(new DocumentErrorResponse(
                        "INVALID_DOCUMENT_REQUEST", "The document upload request is invalid."));
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> causeType) {
        Throwable cause = exception;
        while (cause != null) {
            if (causeType.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
