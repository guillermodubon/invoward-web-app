package io.github.guillermodubon.invoward.extraction.api.model;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReviewUpdate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Editable business data and concurrency token for one document's extraction draft. */
public record UpdateExtractedDocumentRequest(
        @NotNull UUID documentId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull DocumentType confirmedType,
        @Size(max = 240) String vendorName,
        @Size(max = 120) String documentNumber,
        LocalDate documentDate,
        @Size(max = 3) String currency,
        @Digits(integer = 15, fraction = 4) @PositiveOrZero BigDecimal subtotal,
        @Digits(integer = 15, fraction = 4) @PositiveOrZero BigDecimal discountTotal,
        @Digits(integer = 15, fraction = 4) @PositiveOrZero BigDecimal taxTotal,
        @Digits(integer = 15, fraction = 4) @PositiveOrZero BigDecimal total,
        @NotNull @Size(max = 500) List<@NotNull @Valid UpdateExtractedLineRequest> lines) {

    public UpdateExtractedDocumentRequest {
        if (lines != null) {
            lines = List.copyOf(lines);
        }
    }

    public ExtractionReviewUpdate.DocumentUpdate toApplicationUpdate() {
        return new ExtractionReviewUpdate.DocumentUpdate(
                documentId,
                expectedVersion,
                confirmedType,
                vendorName,
                documentNumber,
                documentDate,
                currency,
                subtotal,
                discountTotal,
                taxTotal,
                total,
                lines.stream().map(UpdateExtractedLineRequest::toApplicationUpdate).toList());
    }
}
