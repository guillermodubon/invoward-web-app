package io.github.guillermodubon.invoward.insights.api.exception;

/** Safe transport-level error for an invalid supplier-key path segment. */
public final class InvalidSupplierKeyException extends RuntimeException {

    public InvalidSupplierKeyException() {
        super("Supplier key is invalid.");
    }
}
