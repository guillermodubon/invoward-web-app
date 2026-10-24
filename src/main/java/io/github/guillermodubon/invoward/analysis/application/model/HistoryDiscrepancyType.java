package io.github.guillermodubon.invoward.analysis.application.model;

/** Public discrepancy filter values exposed by the Analysis history query contract. */
public enum HistoryDiscrepancyType {
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
