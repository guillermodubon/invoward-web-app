package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.extraction.application.exception.AnalysisExtractionNotAllowedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ConfirmedDocumentTypeInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentMaterializationException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentTypesNotDetectedException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionAlreadyConfirmedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionLockedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionReviewException;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionOutputException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps extraction API failures to stable responses without provider, storage, or ownership details. */
@RestControllerAdvice(basePackageClasses = DocumentTypeDetectionController.class)
public class ExtractionExceptionHandler {

    @ExceptionHandler(AnalysisNotFoundException.class)
    public ResponseEntity<ExtractionErrorResponse> handleAnalysisNotFound(AnalysisNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "Analysis was not found.");
    }

    @ExceptionHandler(DocumentsRequiredException.class)
    public ResponseEntity<ExtractionErrorResponse> handleDocumentsRequired(DocumentsRequiredException exception) {
        return response(HttpStatus.CONFLICT, "DOCUMENTS_REQUIRED",
                "A reference document and an invoice are required.");
    }

    @ExceptionHandler(AnalysisExtractionNotAllowedException.class)
    public ResponseEntity<ExtractionErrorResponse> handleExtractionNotAllowed(
            AnalysisExtractionNotAllowedException exception) {
        return response(HttpStatus.CONFLICT, "ANALYSIS_EXTRACTION_NOT_ALLOWED",
                "Extraction is not allowed in the current Analysis state.");
    }

    @ExceptionHandler(DocumentTypesNotDetectedException.class)
    public ResponseEntity<ExtractionErrorResponse> handleDocumentTypesNotDetected(
            DocumentTypesNotDetectedException exception) {
        return response(HttpStatus.CONFLICT, "DOCUMENT_TYPES_NOT_DETECTED",
                "Document types must be detected before extraction.");
    }

    @ExceptionHandler(ConfirmedDocumentTypeInvalidException.class)
    public ResponseEntity<ExtractionErrorResponse> handleConfirmedDocumentTypeInvalid(
            ConfirmedDocumentTypeInvalidException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "The request contains invalid data.");
    }

    @ExceptionHandler(ExtractionAlreadyConfirmedException.class)
    public ResponseEntity<ExtractionErrorResponse> handleExtractionAlreadyConfirmed(
            ExtractionAlreadyConfirmedException exception) {
        return response(HttpStatus.CONFLICT, "EXTRACTION_ALREADY_CONFIRMED",
                "The extraction has already been confirmed.");
    }

    @ExceptionHandler(ExtractionConflictException.class)
    public ResponseEntity<ExtractionErrorResponse> handleExtractionConflict(ExtractionConflictException exception) {
        return response(HttpStatus.CONFLICT, "EXTRACTION_CONFLICT",
                "The extraction could not be started in the current state.");
    }

    @ExceptionHandler(ExtractionLockedException.class)
    public ResponseEntity<ExtractionErrorResponse> handleExtractionLocked(ExtractionLockedException exception) {
        return response(HttpStatus.CONFLICT, "EXTRACTION_LOCKED", "The extraction can no longer be edited.");
    }

    @ExceptionHandler(InvalidExtractionReviewException.class)
    public ResponseEntity<ExtractionErrorResponse> handleInvalidExtractionReview(
            InvalidExtractionReviewException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request contains invalid data.");
    }

    @ExceptionHandler(ExtractionNotFoundException.class)
    public ResponseEntity<ExtractionErrorResponse> handleExtractionNotFound(
            ExtractionNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "EXTRACTION_NOT_FOUND", "Extraction was not found.");
    }

    @ExceptionHandler(InvalidExtractionOutputException.class)
    public ResponseEntity<ExtractionErrorResponse> handleInvalidExtractionOutput(
            InvalidExtractionOutputException exception) {
        return response(HttpStatus.BAD_GATEWAY, "AI_RESPONSE_INVALID",
                "Document intelligence returned an invalid response.");
    }

    @ExceptionHandler(DocumentClassificationConflictException.class)
    public ResponseEntity<ExtractionErrorResponse> handleClassificationConflict(
            DocumentClassificationConflictException exception) {
        return response(HttpStatus.CONFLICT, "EXTRACTION_CONFLICT",
                "Document classification is not available in the current Analysis state.");
    }

    @ExceptionHandler({DocumentClassificationInvalidException.class})
    public ResponseEntity<ExtractionErrorResponse> handleInvalidClassification(
            DocumentClassificationInvalidException exception) {
        return response(HttpStatus.BAD_GATEWAY, "AI_RESPONSE_INVALID",
                "Document intelligence returned an invalid response.");
    }

    @ExceptionHandler(DocumentIntelligenceException.class)
    public ResponseEntity<ExtractionErrorResponse> handleDocumentIntelligence(
            DocumentIntelligenceException exception) {
        if (exception.failure() == DocumentIntelligenceException.Failure.INVALID_RESPONSE) {
            return response(HttpStatus.BAD_GATEWAY, "AI_RESPONSE_INVALID",
                    "Document intelligence returned an invalid response.");
        }
        return response(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE",
                "Document intelligence is temporarily unavailable.");
    }

    @ExceptionHandler({DocumentStorageException.class, DocumentMaterializationException.class})
    public ResponseEntity<ExtractionErrorResponse> handleDocumentStorage(RuntimeException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_STORAGE_UNAVAILABLE",
                "Document storage is unavailable.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ExtractionErrorResponse> handleInvalidRequest(
            MethodArgumentTypeMismatchException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ExtractionErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request contains invalid data.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ExtractionErrorResponse> handleUnreadableRequest(
            HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
    }

    private static ResponseEntity<ExtractionErrorResponse> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ExtractionErrorResponse(code, message));
    }
}
