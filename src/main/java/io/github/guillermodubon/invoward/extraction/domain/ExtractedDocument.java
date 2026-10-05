package io.github.guillermodubon.invoward.extraction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ExtractedDocument(
        ExtractionStatus status,
        ExtractionSource extractionSource,
        Instant confirmedAt,
        String vendorName,
        String documentNumber,
        LocalDate documentDate,
        String currency,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal total,
        List<ExtractedLineItem> lines) {

    private static final int MAX_VENDOR_NAME_CODE_POINTS = 240;
    private static final int MAX_DOCUMENT_NUMBER_CODE_POINTS = 120;
    private static final int MAX_LINES = 500;

    public ExtractedDocument {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(extractionSource, "extractionSource must not be null");
        if ((status == ExtractionStatus.CONFIRMED) != (confirmedAt != null)) {
            throw new IllegalArgumentException("confirmedAt must match extraction status");
        }
        vendorName = optionalText(vendorName, MAX_VENDOR_NAME_CODE_POINTS, "vendorName");
        documentNumber = optionalText(documentNumber, MAX_DOCUMENT_NUMBER_CODE_POINTS, "documentNumber");
        currency = normalizeCurrency(currency);
        subtotal = Numeric19_4.requireNonNegative(subtotal, "subtotal");
        discountTotal = Numeric19_4.requireNonNegative(discountTotal, "discountTotal");
        taxTotal = Numeric19_4.requireNonNegative(taxTotal, "taxTotal");
        total = Numeric19_4.requireNonNegative(total, "total");
        lines = List.copyOf(lines);
        if (lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("lines must not exceed 500 items");
        }
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).position() != index) {
                throw new IllegalArgumentException("line positions must be contiguous and zero-based");
            }
        }
    }

    public static ExtractedDocument draft(
            ExtractionSource source,
            String vendorName,
            String documentNumber,
            LocalDate documentDate,
            String currency,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            List<ExtractedLineItem> lines) {
        return new ExtractedDocument(
                ExtractionStatus.DRAFT, source, null, vendorName, documentNumber, documentDate, currency,
                subtotal, discountTotal, taxTotal, total, lines);
    }

    public ExtractedDocument confirm(Instant confirmedAt) {
        if (status != ExtractionStatus.DRAFT) {
            throw new IllegalStateException("only a draft extraction can be confirmed");
        }
        return new ExtractedDocument(
                ExtractionStatus.CONFIRMED, extractionSource, Objects.requireNonNull(confirmedAt, "confirmedAt"),
                vendorName, documentNumber, documentDate, currency, subtotal, discountTotal, taxTotal, total, lines);
    }

    private static String optionalText(String value, int maxCodePoints, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.codePointCount(0, stripped.length()) > maxCodePoints) {
            throw new IllegalArgumentException(field + " must not exceed " + maxCodePoints + " code points");
        }
        return stripped;
    }

    private static String normalizeCurrency(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must contain exactly three uppercase ASCII letters");
        }
        return normalized;
    }
}
