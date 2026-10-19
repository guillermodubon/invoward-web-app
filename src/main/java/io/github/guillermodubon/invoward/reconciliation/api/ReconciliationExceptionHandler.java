package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisProcessingNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisReconciliationNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputChangedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputNotReadyException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps reconciliation failures to stable responses without leaking provider or persistence details. */
@RestControllerAdvice(assignableTypes = ReconciliationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReconciliationExceptionHandler {

    @ExceptionHandler(AnalysisNotFoundException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleAnalysisNotFound(
            AnalysisNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "Analysis was not found.");
    }

    @ExceptionHandler(AnalysisProcessingNotAllowedException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleProcessingNotAllowed(
            AnalysisProcessingNotAllowedException exception) {
        return response(HttpStatus.CONFLICT, "ANALYSIS_PROCESSING_NOT_ALLOWED",
                "Analysis processing is not allowed in its current state.");
    }

    @ExceptionHandler(DiscrepancyNotFoundException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleDiscrepancyNotFound(
            DiscrepancyNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "DISCREPANCY_NOT_FOUND", "Discrepancy was not found.");
    }

    @ExceptionHandler(ReconciliationNotFoundException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleReconciliationNotFound(
            ReconciliationNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "RECONCILIATION_NOT_FOUND", "Reconciliation was not found.");
    }

    @ExceptionHandler(ReconciliationInputNotReadyException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleInputNotReady(
            ReconciliationInputNotReadyException exception) {
        return response(HttpStatus.CONFLICT, "RECONCILIATION_INPUT_NOT_READY",
                "Confirmed reconciliation input is not ready.");
    }

    @ExceptionHandler(ReconciliationConflictException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleConflict(ReconciliationConflictException exception) {
        return response(HttpStatus.CONFLICT, "RECONCILIATION_CONFLICT",
                "The persisted reconciliation state is inconsistent.");
    }

    @ExceptionHandler(DiscrepancyConflictException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleDiscrepancyConflict(
            DiscrepancyConflictException exception) {
        return response(HttpStatus.CONFLICT, "DISCREPANCY_CONFLICT",
                "The discrepancy changed while it was being reviewed. Reload and try again.");
    }

    @ExceptionHandler(ReconciliationInputChangedException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleInputChanged(
            ReconciliationInputChangedException exception) {
        return response(HttpStatus.CONFLICT, "RECONCILIATION_INPUT_CHANGED",
                "The reconciliation input changed before completion.");
    }

    @ExceptionHandler(AnalysisReconciliationNotAllowedException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleNotAllowed(
            AnalysisReconciliationNotAllowedException exception) {
        return response(HttpStatus.CONFLICT, "ANALYSIS_RECONCILIATION_NOT_ALLOWED",
                "Analysis reconciliation is not allowed in its current state.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ReconciliationErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request contains invalid data.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ReconciliationErrorResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
    }

    private static ResponseEntity<ReconciliationErrorResponse> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ReconciliationErrorResponse(code, message));
    }
}
