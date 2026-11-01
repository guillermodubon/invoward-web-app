package io.github.guillermodubon.invoward.insights.api.exception;

/** Safe transport-level error for invalid supplier-list filters and pagination. */
public final class InvalidSupplierQueryException extends RuntimeException {

    public InvalidSupplierQueryException() {
        super("Supplier query is invalid.");
    }
}
