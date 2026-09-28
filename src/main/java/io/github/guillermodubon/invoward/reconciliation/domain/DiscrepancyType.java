package io.github.guillermodubon.invoward.reconciliation.domain;

public enum DiscrepancyType {
    QUANTITY_MISMATCH,
    UNIT_PRICE_MISMATCH,
    MISSING_ITEM,
    UNEXPECTED_ITEM,
    DISCOUNT_MISMATCH,
    TAX_MISMATCH,
    SUBTOTAL_MISMATCH,
    TOTAL_MISMATCH,
    CURRENCY_MISMATCH
}
