package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.api.model.AnalysisErrorResponse;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps Analysis API failures to safe, stable JSON responses. */
@RestControllerAdvice(basePackageClasses = AnalysisController.class)
public class AnalysisExceptionHandler {

    @ExceptionHandler(AnalysisNotFoundException.class)
    public ResponseEntity<AnalysisErrorResponse> handleNotFound(AnalysisNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AnalysisErrorResponse("ANALYSIS_NOT_FOUND", "Analysis was not found."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AnalysisErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(new AnalysisErrorResponse("VALIDATION_FAILED", "The request contains invalid data."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AnalysisErrorResponse> handleUnreadableRequest(
            HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new AnalysisErrorResponse("INVALID_REQUEST", "The request is invalid."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<AnalysisErrorResponse> handlePathParameterTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(new AnalysisErrorResponse("INVALID_REQUEST", "The request is invalid."));
    }
}
