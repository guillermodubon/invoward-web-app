package io.github.guillermodubon.invoward.extraction.domain;

import java.math.BigDecimal;

public record ExtractedLineItem(
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
        BoundingBox boundingBox) {

    private static final int MAX_ITEM_CODE_CODE_POINTS = 120;
    private static final int MAX_DESCRIPTION_CODE_POINTS = 2000;
    private static final int MAX_UNIT_CODE_POINTS = 50;

    public ExtractedLineItem {
        if (position < 0) {
            throw new IllegalArgumentException("position must not be negative");
        }
        itemCode = optionalText(itemCode, MAX_ITEM_CODE_CODE_POINTS, "itemCode");
        description = requiredText(description, MAX_DESCRIPTION_CODE_POINTS, "description");
        quantity = Numeric19_4.requirePositive(quantity, "quantity");
        unit = optionalText(unit, MAX_UNIT_CODE_POINTS, "unit");
        unitPrice = Numeric19_4.requireNonNegative(unitPrice, "unitPrice");
        discountAmount = Numeric19_4.requireNonNegative(discountAmount, "discountAmount");
        taxAmount = Numeric19_4.requireNonNegative(taxAmount, "taxAmount");
        lineTotal = Numeric19_4.requireNonNegative(lineTotal, "lineTotal");
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be positive when present");
        }
        if (sourceText != null && sourceText.isBlank()) {
            sourceText = null;
        }
    }

    public String normalizedDescription() {
        return DescriptionNormalizer.normalize(description);
    }

    private static String optionalText(String value, int maxCodePoints, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        requireLength(stripped, maxCodePoints, field);
        return stripped;
    }

    private static String requiredText(String value, int maxCodePoints, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String stripped = value.strip();
        requireLength(stripped, maxCodePoints, field);
        return stripped;
    }

    private static void requireLength(String value, int maxCodePoints, String field) {
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException(field + " must not exceed " + maxCodePoints + " code points");
        }
    }
}
