package io.github.guillermodubon.invoward.insights.api.exception;

/** Safe transport-level error for invalid supplier-history filters and pagination. */
public final class InvalidSupplierHistoryQueryException extends RuntimeException {

    public InvalidSupplierHistoryQueryException() {
        super("Supplier history query is invalid.");
    }
}
