package io.github.guillermodubon.invoward.extraction.api.model;

import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;

import java.math.BigDecimal;
import java.util.UUID;

public record ExtractedLineItemResponse(
        UUID id,
        int position,
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
        BoundingBoxResponse boundingBox) {

    public static ExtractedLineItemResponse from(UUID id, ExtractedLineItem line) {
        return new ExtractedLineItemResponse(
                id, line.position(), line.itemCode(), line.description(), line.quantity(), line.unit(),
                line.unitPrice(), line.discountAmount(), line.taxAmount(), line.lineTotal(), line.pageNumber(),
                line.sourceText(), BoundingBoxResponse.from(line.boundingBox()));
    }
}
