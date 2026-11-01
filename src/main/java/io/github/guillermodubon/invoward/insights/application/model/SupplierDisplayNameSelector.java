package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Objects;

/** Applies the display fallback after persistence has selected the latest completed nonblank label. */
public final class SupplierDisplayNameSelector {

    private SupplierDisplayNameSelector() {
    }

    /** Preserves the selected label exactly; absent or blank labels fall back to the canonical key. */
    public static String select(SupplierKey supplierKey, String latestCompletedNonblankName) {
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        return latestCompletedNonblankName == null || latestCompletedNonblankName.isBlank()
                ? supplierKey.value()
                : latestCompletedNonblankName;
    }
}
