package io.github.guillermodubon.invoward.extraction.api.model;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import jakarta.validation.constraints.NotNull;

/** Human-confirmed document types required before extraction can start. */
public record StartExtractionRequest(
        @NotNull(message = "Reference type is required") DocumentType referenceType,
        @NotNull(message = "Invoice type is required") DocumentType invoiceType) {

    public ExtractionRequestTypes toRequestTypes() {
        return new ExtractionRequestTypes(referenceType, invoiceType);
    }
}
