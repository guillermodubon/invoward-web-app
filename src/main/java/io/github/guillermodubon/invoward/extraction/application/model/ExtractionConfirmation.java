package io.github.guillermodubon.invoward.extraction.application.model;

import java.util.Objects;
import java.util.UUID;

/** Client-observed document identities and versions required to confirm one extraction review. */
public record ExtractionConfirmation(
        UUID referenceDocumentId,
        long referenceExpectedVersion,
        UUID invoiceDocumentId,
        long invoiceExpectedVersion) {

    public ExtractionConfirmation {
        Objects.requireNonNull(referenceDocumentId, "referenceDocumentId must not be null");
        Objects.requireNonNull(invoiceDocumentId, "invoiceDocumentId must not be null");
        if (referenceDocumentId.equals(invoiceDocumentId)) {
            throw new IllegalArgumentException("reference and invoice document ids must differ");
        }
        if (referenceExpectedVersion < 0 || invoiceExpectedVersion < 0) {
            throw new IllegalArgumentException("expected versions must not be negative");
        }
    }
}
