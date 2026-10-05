package io.github.guillermodubon.invoward.extraction.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Structured extraction output before application/domain validation. */
public record ExtractionDraft(
        String vendorName,
        String documentNumber,
        String documentDate,
        String currency,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal total,
        List<Line> lines) {

    public ExtractionDraft {
        lines = lines == null ? null : Collections.unmodifiableList(new ArrayList<>(lines));
    }

    public record Line(
            String itemCode,
            String description,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            BigDecimal lineTotal,
            Integer pageNumber,
            String sourceText,
            RawBoundingBox boundingBox) {
    }

    public record RawBoundingBox(Double xMin, Double yMin, Double xMax, Double yMax) {
    }
}
