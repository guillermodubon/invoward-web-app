package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Objects;

/** Exact persisted supplier key; HTTP path-segment safety belongs to the API boundary. */
public record SupplierKey(String value) {

    public static final int MAX_CODE_POINTS = 240;

    public SupplierKey {
        Objects.requireNonNull(value, "supplier key is required");
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount < 1 || codePointCount > MAX_CODE_POINTS || value.isBlank()) {
            throw new IllegalArgumentException("Supplier key is invalid.");
        }
        if (containsMalformedSurrogate(value)) {
            throw new IllegalArgumentException("Supplier key is invalid.");
        }
    }

    private static boolean containsMalformedSurrogate(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }
}
