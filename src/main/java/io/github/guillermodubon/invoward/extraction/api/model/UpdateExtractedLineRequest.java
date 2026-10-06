package io.github.guillermodubon.invoward.extraction.api.model;

import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReviewUpdate;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** Editable line fields only; provider evidence and line position are intentionally absent. */
public record UpdateExtractedLineRequest(
        UUID id,
        @Size(max = 120) String itemCode,
        @NotBlank @Size(max = 2000) String description,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4)
                BigDecimal quantity,
        @Size(max = 50) String unit,
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal discountAmount,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal taxAmount,
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal lineTotal) {

    public ExtractionReviewUpdate.LineUpdate toApplicationUpdate() {
        return new ExtractionReviewUpdate.LineUpdate(
                id, itemCode, description, quantity, unit, unitPrice, discountAmount, taxAmount, lineTotal);
    }
}
