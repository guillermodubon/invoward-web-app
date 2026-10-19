package io.github.guillermodubon.invoward.processing.api;

import io.github.guillermodubon.invoward.analysis.api.model.AnalysisErrorResponse;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.processing.application.exception.ProcessingRetryNotAllowedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps retry API failures to the same safe Analysis error contract. */
@RestControllerAdvice(basePackageClasses = AnalysisRetryController.class)
public class ProcessingApiExceptionHandler {

    @ExceptionHandler(AnalysisNotFoundException.class)
    public ResponseEntity<AnalysisErrorResponse> handleNotFound(AnalysisNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AnalysisErrorResponse("ANALYSIS_NOT_FOUND", "Analysis was not found."));
    }

    @ExceptionHandler(ProcessingRetryNotAllowedException.class)
    public ResponseEntity<AnalysisErrorResponse> handleRetryNotAllowed(
            ProcessingRetryNotAllowedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AnalysisErrorResponse(
                        "ANALYSIS_RETRY_NOT_ALLOWED", "Analysis retry is not allowed in its current state."));
    }
}
