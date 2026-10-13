package io.github.guillermodubon.invoward.reconciliation.application.model;

/** A non-blocking signal explaining why an earlier invoice may be a duplicate. */
public enum DuplicateInvoiceSignal {
    EXACT_INVOICE_FILE_HASH,
    SAME_INVOICE_NUMBER,
    SAME_SUPPLIER,
    SAME_TOTAL,
    SAME_DOCUMENT_DATE
}
