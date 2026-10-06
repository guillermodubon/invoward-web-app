package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;

import java.util.Objects;

/** The single V1 reference/invoice pair in deterministic role order. */
public record ExtractionDocumentPair(Document reference, Document invoice) {

    public ExtractionDocumentPair {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(invoice, "invoice must not be null");
        if (reference.role() != DocumentRole.REFERENCE || invoice.role() != DocumentRole.INVOICE
                || reference.id().equals(invoice.id())) {
            throw new IllegalArgumentException("pair must contain distinct reference and invoice documents");
        }
    }

    @Override
    public String toString() {
        return "ExtractionDocumentPair[referencePresent=true, invoicePresent=true]";
    }
}
