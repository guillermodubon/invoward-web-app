package io.github.guillermodubon.invoward.insights.application.exception;

/** The exact supplier key is not present among this registered owner's completed analyses. */
public final class SupplierNotFoundException extends RuntimeException {

    public SupplierNotFoundException() {
        super("Supplier was not found.");
    }
}
