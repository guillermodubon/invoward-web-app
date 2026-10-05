package io.github.guillermodubon.invoward.extraction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtractedDocumentTest {

    @Test
    void normalizesHeaderFieldsAndCopiesLines() {
        ExtractedLineItem line = line(0, "Item");
        ExtractedDocument document = ExtractedDocument.draft(
                ExtractionSource.AI, "  Café Supplies  ", " INV-1 ", null, " usd ",
                new BigDecimal("10"), null, null, new BigDecimal("10.00000"), List.of(line));

        assertEquals(ExtractionStatus.DRAFT, document.status());
        assertEquals(ExtractionSource.AI, document.extractionSource());
        assertEquals("Café Supplies", document.vendorName());
        assertEquals("INV-1", document.documentNumber());
        assertEquals("USD", document.currency());
        assertEquals(new BigDecimal("10.0000"), document.total());
        assertEquals(List.of(line), document.lines());
    }

    @Test
    void rejectsMalformedCurrencyNegativeMoneyAndOverlongHeader() {
        assertThrows(IllegalArgumentException.class, () -> document("US"));
        assertThrows(IllegalArgumentException.class, () -> extracted("CURRENCY", "US", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> extracted("SUBTOTAL", null, new BigDecimal("-1"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> withVendor(
                extracted("VENDOR", "valid", null, List.of()), "😀".repeat(241)));
    }

    @Test
    void confirmsOnlyDraftsAndKeepsTheirSource() {
        Instant confirmedAt = Instant.parse("2026-05-01T12:00:00Z");
        ExtractedDocument draft = extracted(null, null, null, List.of());

        ExtractedDocument confirmed = draft.confirm(confirmedAt);

        assertEquals(ExtractionStatus.CONFIRMED, confirmed.status());
        assertEquals(ExtractionSource.CACHE, confirmed.extractionSource());
        assertEquals(confirmedAt, confirmed.confirmedAt());
        assertThrows(IllegalStateException.class, () -> confirmed.confirm(confirmedAt.plusSeconds(1)));
    }

    @Test
    void enforcesTheMaximumAndContiguousZeroBasedLinePositions() {
        List<ExtractedLineItem> tooMany = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> line(index, "Item"))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> extracted(null, null, null, tooMany));
        assertThrows(IllegalArgumentException.class, () -> extracted(null, null, null, List.of(line(1, "Item"))));
    }

    private static ExtractedDocument document(String currency) {
        return extracted("CURRENCY", currency, null, List.of());
    }

    private static ExtractedDocument extracted(
            String field, String value, BigDecimal subtotal, List<ExtractedLineItem> lines) {
        return ExtractedDocument.draft(
                ExtractionSource.CACHE,
                "VENDOR".equals(field) ? value : null,
                null,
                null,
                "CURRENCY".equals(field) ? value : null,
                "SUBTOTAL".equals(field) ? subtotal : null,
                null,
                null,
                null,
                lines);
    }

    private static ExtractedDocument withVendor(ExtractedDocument original, String vendorName) {
        return ExtractedDocument.draft(
                original.extractionSource(), vendorName, original.documentNumber(), original.documentDate(),
                original.currency(), original.subtotal(), original.discountTotal(), original.taxTotal(),
                original.total(), original.lines());
    }

    private static ExtractedLineItem line(int position, String description) {
        return new ExtractedLineItem(
                position, null, description, null, null, null, null, null, null, null, null, null);
    }
}
