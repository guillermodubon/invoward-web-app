package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reporting.api.model.ReportingErrorResponse;
import io.github.guillermodubon.invoward.reporting.application.exception.AnalysisReportNotAllowedException;
import io.github.guillermodubon.invoward.reporting.application.exception.CsvExportTooLargeException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportConflictException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportGenerationException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportInputNotReadyException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportNotFoundException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps reporting failures to stable responses without exposing implementation or storage details. */
@RestControllerAdvice(basePackageClasses = ReportController.class)
public class ReportingExceptionHandler {

    @ExceptionHandler(AnalysisNotFoundException.class)
    public ResponseEntity<ReportingErrorResponse> handleAnalysisNotFound(AnalysisNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "Analysis was not found.");
    }

    @ExceptionHandler(ReportNotFoundException.class)
    public ResponseEntity<ReportingErrorResponse> handleReportNotFound(ReportNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "Report was not found.");
    }

    @ExceptionHandler(ReconciliationNotFoundException.class)
    public ResponseEntity<ReportingErrorResponse> handleReconciliationNotFound(
            ReconciliationNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "RECONCILIATION_NOT_FOUND", "Reconciliation was not found.");
    }

    @ExceptionHandler(ReconciliationConflictException.class)
    public ResponseEntity<ReportingErrorResponse> handleReconciliationConflict(
            ReconciliationConflictException exception) {
        return response(HttpStatus.CONFLICT, "REPORT_CONFLICT",
                "The persisted reconciliation cannot be exported in its current state.");
    }

    @ExceptionHandler(CsvExportTooLargeException.class)
    public ResponseEntity<ReportingErrorResponse> handleCsvExportTooLarge(CsvExportTooLargeException exception) {
        return response(HttpStatus.CONTENT_TOO_LARGE, "EXPORT_TOO_LARGE",
                "The CSV export exceeds the configured size limit.");
    }

    @ExceptionHandler(ReportInputNotReadyException.class)
    public ResponseEntity<ReportingErrorResponse> handleInputNotReady(ReportInputNotReadyException exception) {
        return response(HttpStatus.CONFLICT, "REPORT_INPUT_NOT_READY",
                "The persisted reconciliation is not ready for report generation.");
    }

    @ExceptionHandler(ReportConflictException.class)
    public ResponseEntity<ReportingErrorResponse> handleConflict(ReportConflictException exception) {
        return response(HttpStatus.CONFLICT, "REPORT_CONFLICT", "The report conflicts with the current state.");
    }

    @ExceptionHandler(AnalysisReportNotAllowedException.class)
    public ResponseEntity<ReportingErrorResponse> handleReportNotAllowed(
            AnalysisReportNotAllowedException exception) {
        return response(HttpStatus.CONFLICT, "ANALYSIS_REPORT_NOT_ALLOWED",
                "Report generation is not allowed in the current Analysis state.");
    }

    @ExceptionHandler(ReportGenerationException.class)
    public ResponseEntity<ReportingErrorResponse> handleGenerationFailure(ReportGenerationException exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_GENERATION_FAILED",
                "The report could not be generated.");
    }

    @ExceptionHandler(ReportStorageException.class)
    public ResponseEntity<ReportingErrorResponse> handleStorageFailure(ReportStorageException exception) {
        if (exception.failure() == ReportStorageException.Failure.UNAVAILABLE) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, "REPORT_STORAGE_UNAVAILABLE",
                    "Report storage is temporarily unavailable.");
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_GENERATION_FAILED",
                "The report could not be generated.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ReportingErrorResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
    }

    private static ResponseEntity<ReportingErrorResponse> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ReportingErrorResponse(code, message));
    }
}
