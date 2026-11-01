package io.github.guillermodubon.invoward.insights.api;

import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierKeyException;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;

/** Validates the URL-decoded supplier key without changing its persisted identity. */
public final class SupplierKeyQueryValidator {

    private SupplierKeyQueryValidator() {
    }

    /**
     * Returns the exact key supplied by the HTTP path after applying the value object's bounds and
     * path-safety checks. This deliberately does not trim, case-fold, or Unicode-normalize it.
     */
    public static SupplierKey validate(String urlDecodedSupplierKey) {
        if (urlDecodedSupplierKey == null || containsPathUnsafeCharacter(urlDecodedSupplierKey)) {
            throw new InvalidSupplierKeyException();
        }
        try {
            return new SupplierKey(urlDecodedSupplierKey);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSupplierKeyException();
        }
    }

    private static boolean containsPathUnsafeCharacter(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    || codePoint == '/'
                    || codePoint == '\\'
                    || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return value.equals(".") || value.equals("..");
    }
}
