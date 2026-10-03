package io.github.guillermodubon.invoward.document.api;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.document.api.model.DocumentErrorResponse;
import io.github.guillermodubon.invoward.document.application.exception.DocumentNotFoundException;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentSizeLimitExceededException;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentsLockedException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentRoleAlreadyExistsException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Maps document upload failures to stable responses without exposing parser or provider details. */
@RestControllerAdvice(basePackageClasses = DocumentController.class)
public class DocumentExceptionHandler {

    @ExceptionHandler(AnalysisNotFoundException.class)
    public ResponseEntity<DocumentErrorResponse> handleAnalysisNotFound(AnalysisNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "Analysis was not found.");
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<DocumentErrorResponse> handleDocumentNotFound(DocumentNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document was not found.");
    }

    @ExceptionHandler(AnalysisDocumentSizeLimitExceededException.class)
    public ResponseEntity<DocumentErrorResponse> handleCombinedSizeExceeded(
            AnalysisDocumentSizeLimitExceededException exception) {
        return response(HttpStatus.CONTENT_TOO_LARGE,
                "ANALYSIS_DOCUMENT_SIZE_LIMIT_EXCEEDED", "Analysis documents exceed the configured size limit.");
    }

    @ExceptionHandler(DocumentRoleAlreadyExistsException.class)
    public ResponseEntity<DocumentErrorResponse> handleRoleAlreadyExists(
            DocumentRoleAlreadyExistsException exception) {
        return response(HttpStatus.CONFLICT,
                "DOCUMENT_ROLE_ALREADY_EXISTS", "A document for this role already exists.");
    }

    @ExceptionHandler(AnalysisDocumentsLockedException.class)
    public ResponseEntity<DocumentErrorResponse> handleAnalysisLocked(AnalysisDocumentsLockedException exception) {
        return response(HttpStatus.CONFLICT, "ANALYSIS_DOCUMENTS_LOCKED", "Analysis no longer accepts documents.");
    }

    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<DocumentErrorResponse> handleStorageUnavailable(DocumentStorageException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                "DOCUMENT_STORAGE_UNAVAILABLE", "Document storage is unavailable.");
    }

    @ExceptionHandler(DocumentUploadValidationException.class)
    public ResponseEntity<DocumentErrorResponse> handleValidation(DocumentUploadValidationException exception) {
        return switch (exception.failure()) {
            case TOO_LARGE -> response(HttpStatus.CONTENT_TOO_LARGE,
                    "DOCUMENT_TOO_LARGE", "Document exceeds the configured size limit.");
            case UNSUPPORTED_TYPE -> response(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "DOCUMENT_TYPE_UNSUPPORTED", "Document type is not supported.");
            case TYPE_MISMATCH -> response(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "DOCUMENT_TYPE_MISMATCH", "Document extension and content type do not match.");
            case PDF_PASSWORD_PROTECTED -> response(HttpStatus.UNPROCESSABLE_CONTENT,
                    "PDF_PASSWORD_PROTECTED", "Password-protected PDF documents are not supported.");
            case PDF_PAGE_LIMIT_EXCEEDED -> response(HttpStatus.UNPROCESSABLE_CONTENT,
                    "PDF_PAGE_LIMIT_EXCEEDED", "PDF document exceeds the configured page limit.");
            case IMAGE_DIMENSIONS_EXCEEDED -> response(HttpStatus.UNPROCESSABLE_CONTENT,
                    "IMAGE_DIMENSIONS_EXCEEDED", "Image exceeds the configured dimensions.");
            case EMPTY_FILE, INVALID_FILENAME, DOCUMENT_INVALID -> response(HttpStatus.UNPROCESSABLE_CONTENT,
                    "DOCUMENT_INVALID", "Document content is invalid.");
        };
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<DocumentErrorResponse> handleInvalidRequest(Exception exception) {
        return invalidRequest();
    }

    private static ResponseEntity<DocumentErrorResponse> invalidRequest() {
        return response(HttpStatus.BAD_REQUEST,
                "INVALID_DOCUMENT_REQUEST", "The document upload request is invalid.");
    }

    private static ResponseEntity<DocumentErrorResponse> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new DocumentErrorResponse(code, message));
    }

}
