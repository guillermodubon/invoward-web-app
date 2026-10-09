package io.github.guillermodubon.invoward.reconciliation.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Minimal, punctuation-preserving normalization for item identifiers. */
public final class LineItemCodeNormalizer {

    public String normalize(String itemCode) {
        if (itemCode == null) {
            return null;
        }
        String normalized = Normalizer.normalize(itemCode, Normalizer.Form.NFKC)
                .strip()
                .toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
