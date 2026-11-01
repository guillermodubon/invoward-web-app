package io.github.guillermodubon.invoward.insights.api;

import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierKeyException;
import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierHistoryQueryException;
import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierQueryException;
import io.github.guillermodubon.invoward.insights.api.model.InsightsErrorResponse;
import io.github.guillermodubon.invoward.insights.application.exception.SupplierNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InsightsDashboardExceptionHandlerTest {

    @Test
    void invalidSupplierKeyUsesTheStableSafeBadRequestContract() {
        var response = new InsightsDashboardExceptionHandler()
                .handleInvalidSupplierKey(new InvalidSupplierKeyException());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new InsightsErrorResponse("INVALID_SUPPLIER_KEY", "The supplier key is invalid."),
                response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void supplierListQueryUsesTheStableSafeBadRequestContract() {
        var response = new InsightsDashboardExceptionHandler()
                .handleInvalidSupplierQuery(new InvalidSupplierQueryException());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new InsightsErrorResponse("INVALID_SUPPLIER_QUERY", "The supplier query is invalid."),
                response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void supplierHistoryQueryUsesTheStableSafeBadRequestContract() {
        var response = new InsightsDashboardExceptionHandler()
                .handleInvalidSupplierHistoryQuery(new InvalidSupplierHistoryQueryException());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new InsightsErrorResponse(
                        "INVALID_SUPPLIER_HISTORY_QUERY", "The supplier history query is invalid."),
                response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void unknownOwnedSupplierUsesTheStableSafeNotFoundContract() {
        var response = new InsightsDashboardExceptionHandler()
                .handleSupplierNotFound(new SupplierNotFoundException());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(new InsightsErrorResponse("SUPPLIER_NOT_FOUND", "Supplier was not found."),
                response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }
}
