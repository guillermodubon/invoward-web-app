package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.document.domain.DocumentType;

import java.util.Objects;

/** Human-confirmed document types supplied before extraction starts. */
public record ExtractionRequestTypes(DocumentType referenceType, DocumentType invoiceType) {

    public ExtractionRequestTypes {
        Objects.requireNonNull(referenceType, "referenceType must not be null");
        Objects.requireNonNull(invoiceType, "invoiceType must not be null");
    }

    @Override
    public String toString() {
        return "ExtractionRequestTypes[provided=true]";
    }
}
