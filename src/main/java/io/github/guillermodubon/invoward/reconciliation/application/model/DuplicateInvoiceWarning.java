package io.github.guillermodubon.invoward.reconciliation.application.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Non-blocking duplicate-invoice result; candidates contain no hashes or supplier keys. */
public record DuplicateInvoiceWarning(boolean possibleDuplicate, List<DuplicateInvoiceCandidate> candidates) {

    public static final int MAX_CANDIDATES = 5;

    public DuplicateInvoiceWarning {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("candidates must not exceed " + MAX_CANDIDATES);
        }
        if (possibleDuplicate != !candidates.isEmpty()) {
            throw new IllegalArgumentException("possibleDuplicate must match candidate presence");
        }
        if (new HashSet<>(candidates.stream().map(DuplicateInvoiceCandidate::analysisId).toList()).size()
                != candidates.size()) {
            throw new IllegalArgumentException("candidate analysis IDs must be unique");
        }
    }

    public static DuplicateInvoiceWarning none() {
        return new DuplicateInvoiceWarning(false, List.of());
    }
}
