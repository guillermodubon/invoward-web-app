package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionOutputException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderOutputValidatorTest {

    private final ProviderOutputValidator validator = new ProviderOutputValidator(500, 4000);

    @Test
    void validatesAndNormalizesProviderOutputWithoutChangingEvidenceText() {
        ExtractedDocument result = validator.validate(draft(
                "  Acme  ", " INV-7 ", "2026-04-03", " usd ", new BigDecimal("9.5"),
                List.of(line("  Cable  ", new BigDecimal("2"), 2, "  source evidence  ", box(0, 0.1, 0.8, 1)))), 2);

        assertEquals("Acme", result.vendorName());
        assertEquals("INV-7", result.documentNumber());
        assertEquals("USD", result.currency());
        assertEquals(0, result.lines().getFirst().position());
        assertEquals("cable", result.lines().getFirst().normalizedDescription());
        assertEquals("  source evidence  ", result.lines().getFirst().sourceText());
        assertEquals(0.8, result.lines().getFirst().boundingBox().xMax());
    }

    @Test
    void acceptsAbsentDateAndEvidenceAsNull() {
        ExtractedDocument result = validator.validate(
                draft(null, null, null, null, null, List.of(line("Item", null, null, null, null))), 1);
        assertNull(result.documentDate());
        assertNull(result.lines().getFirst().pageNumber());
        assertNull(result.lines().getFirst().sourceText());
        assertNull(result.lines().getFirst().boundingBox());
    }

    @Test
    void rejectsInvalidDatesCurrencyNumbersDescriptionsAndOutputBounds() {
        assertInvalid(draft(null, null, "03/04/2026", "USD", null, List.of()), 1);
        assertInvalid(draft(null, null, null, "US$", null, List.of()), 1);
        assertInvalid(draft(null, null, null, null, new BigDecimal("-0.01"), List.of()), 1);
        assertInvalid(draft(null, null, null, null, new BigDecimal("1.00001"), List.of()), 1);
        assertInvalid(draft(null, null, null, null, null,
                List.of(line(" ", null, null, null, null))), 1);
        assertInvalid(draft("V".repeat(241), null, null, null, null, List.of()), 1);
        assertInvalid(draft(null, null, null, null, null, List.of(line("Item", null, 0, null, null))), 1);
        assertInvalid(draft(null, null, null, null, null, List.of(line("Item", null, 2, null, null))), 1);
        assertInvalid(draft(null, null, null, null, null, List.of(line(
                "Item", null, 1, "x".repeat(4001), null))), 1);
        assertInvalid(draft(null, null, null, null, null, List.of(line(
                "Item", null, null, null, box(0.8, 0.2, 0.1, 0.9)))), 1);
        assertInvalid(draft(null, null, null, null, null, List.of(line(
                "Item", null, null, null, new ExtractionDraft.RawBoundingBox(0.1, null, 0.9, 0.8)))), 1);
        assertInvalid(draft(null, null, null, null, null, null), 1);
        assertInvalid(draft(null, null, null, null, null, List.of()), 0);
    }

    @Test
    void rejectsMoreThanConfiguredLinesWithoutTruncation() {
        ProviderOutputValidator limited = new ProviderOutputValidator(1, 4000);
        ExtractionDraft output = draft(null, null, null, null, null,
                List.of(line("one", null, null, null, null), line("two", null, null, null, null)));
        assertThrows(InvalidExtractionOutputException.class, () -> limited.validate(output, 1));
    }

    @Test
    void exposesOnlyASafeValidationMessage() {
        InvalidExtractionOutputException exception = assertThrows(
                InvalidExtractionOutputException.class,
                () -> validator.validate(draft("sensitive source value", null, "bad date", null, null, List.of()), 1));
        assertEquals("Extraction output failed validation", exception.getMessage());
        assertNull(exception.getCause());
    }

    private void assertInvalid(ExtractionDraft draft, int pageCount) {
        assertThrows(InvalidExtractionOutputException.class, () -> validator.validate(draft, pageCount));
    }

    private static ExtractionDraft draft(
            String vendor,
            String number,
            String date,
            String currency,
            BigDecimal total,
            List<ExtractionDraft.Line> lines) {
        return new ExtractionDraft(vendor, number, date, currency, null, null, null, total, lines);
    }

    private static ExtractionDraft.Line line(
            String description,
            BigDecimal quantity,
            Integer page,
            String source,
            ExtractionDraft.RawBoundingBox box) {
        return new ExtractionDraft.Line(null, description, quantity, null, null, null, null, null, page, source, box);
    }

    private static ExtractionDraft.RawBoundingBox box(double xMin, double yMin, double xMax, double yMax) {
        return new ExtractionDraft.RawBoundingBox(xMin, yMin, xMax, yMax);
    }
}
