package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisMatchingNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchTargetUnavailableException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchesLockedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchesNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchingInputNotReadyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps matching API failures without disclosing ownership or persistence details. */
@RestControllerAdvice(basePackageClasses = LineItemMatchesController.class)
public class LineItemMatchesExceptionHandler {

    @ExceptionHandler(AnalysisNotFoundException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleAnalysisNotFound(
            AnalysisNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "Analysis was not found.");
    }

    @ExceptionHandler(MatchesNotFoundException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleMatchesNotFound(
            MatchesNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "MATCHES_NOT_FOUND", "Matches were not found.");
    }

    @ExceptionHandler(MatchSetConflictException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleMatchSetConflict(
            MatchSetConflictException exception) {
        return response(HttpStatus.CONFLICT, "MATCH_SET_CONFLICT",
                "The persisted match set is inconsistent.");
    }

    @ExceptionHandler(MatchNotFoundException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleMatchNotFound(MatchNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "MATCH_NOT_FOUND", "Match was not found.");
    }

    @ExceptionHandler(MatchConflictException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleMatchConflict(MatchConflictException exception) {
        return response(HttpStatus.CONFLICT, "MATCH_CONFLICT", "The match changed before the update.");
    }

    @ExceptionHandler(MatchTargetUnavailableException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleTargetUnavailable(
            MatchTargetUnavailableException exception) {
        return response(HttpStatus.CONFLICT, "MATCH_TARGET_UNAVAILABLE", "The selected match target is unavailable.");
    }

    @ExceptionHandler(MatchesLockedException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleMatchesLocked(MatchesLockedException exception) {
        return response(HttpStatus.CONFLICT, "MATCHES_LOCKED", "Matches can no longer be changed.");
    }

    @ExceptionHandler(AnalysisMatchingNotAllowedException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleMatchingNotAllowed(
            AnalysisMatchingNotAllowedException exception) {
        return response(HttpStatus.CONFLICT, "ANALYSIS_MATCHING_NOT_ALLOWED",
                "Analysis matching is not allowed in its current state.");
    }

    @ExceptionHandler(MatchingInputNotReadyException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleMatchingInputNotReady(
            MatchingInputNotReadyException exception) {
        return response(HttpStatus.CONFLICT, "MATCHING_INPUT_NOT_READY", "Confirmed matching input is not ready.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<LineItemMatchesErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request contains invalid data.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<LineItemMatchesErrorResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
    }

    private static ResponseEntity<LineItemMatchesErrorResponse> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new LineItemMatchesErrorResponse(code, message));
    }
}
