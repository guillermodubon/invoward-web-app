package io.github.guillermodubon.invoward.insights.api;

import io.github.guillermodubon.invoward.insights.api.exception.InvalidDashboardQueryException;
import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierHistoryQueryException;
import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierKeyException;
import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierQueryException;
import io.github.guillermodubon.invoward.insights.api.model.InsightsErrorResponse;
import io.github.guillermodubon.invoward.insights.application.exception.SupplierNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps dashboard request failures to stable JSON without exposing internal details. */
@RestControllerAdvice(basePackageClasses = InsightsDashboardController.class)
public class InsightsDashboardExceptionHandler {

    @ExceptionHandler(InvalidDashboardQueryException.class)
    public ResponseEntity<InsightsErrorResponse> handleInvalidDashboardQuery(
            InvalidDashboardQueryException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_DASHBOARD_QUERY", "The dashboard query is invalid.");
    }

    @ExceptionHandler(InvalidSupplierKeyException.class)
    public ResponseEntity<InsightsErrorResponse> handleInvalidSupplierKey(InvalidSupplierKeyException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_SUPPLIER_KEY", "The supplier key is invalid.");
    }

    @ExceptionHandler(InvalidSupplierQueryException.class)
    public ResponseEntity<InsightsErrorResponse> handleInvalidSupplierQuery(
            InvalidSupplierQueryException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_SUPPLIER_QUERY", "The supplier query is invalid.");
    }

    @ExceptionHandler(InvalidSupplierHistoryQueryException.class)
    public ResponseEntity<InsightsErrorResponse> handleInvalidSupplierHistoryQuery(
            InvalidSupplierHistoryQueryException exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_SUPPLIER_HISTORY_QUERY",
                "The supplier history query is invalid.");
    }

    @ExceptionHandler(SupplierNotFoundException.class)
    public ResponseEntity<InsightsErrorResponse> handleSupplierNotFound(SupplierNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Supplier was not found.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<InsightsErrorResponse> handleUnexpectedFailure(Exception exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed.");
    }

    private static ResponseEntity<InsightsErrorResponse> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new InsightsErrorResponse(code, message));
    }
}
