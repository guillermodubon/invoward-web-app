package io.github.guillermodubon.invoward.extraction.api.model;

import io.github.guillermodubon.invoward.extraction.application.model.ExtractionConfirmation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/** Request identities and versions observed by the user before confirming extraction review. */
public record ConfirmExtractionRequest(
        @NotNull UUID referenceDocumentId,
        @NotNull @PositiveOrZero Long referenceExpectedVersion,
        @NotNull UUID invoiceDocumentId,
        @NotNull @PositiveOrZero Long invoiceExpectedVersion) {

    public ConfirmExtractionRequest {
        if (referenceDocumentId != null && referenceDocumentId.equals(invoiceDocumentId)) {
            throw new IllegalArgumentException("reference and invoice document ids must differ");
        }
    }

    public ExtractionConfirmation toApplicationConfirmation() {
        return new ExtractionConfirmation(
                referenceDocumentId,
                referenceExpectedVersion,
                invoiceDocumentId,
                invoiceExpectedVersion);
    }
}
