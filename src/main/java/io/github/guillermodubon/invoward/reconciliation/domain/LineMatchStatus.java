package io.github.guillermodubon.invoward.reconciliation.domain;

public enum LineMatchStatus {
    MATCHED,
    NEEDS_REVIEW,
    UNMATCHED_REFERENCE,
    UNMATCHED_INVOICE
}
