package io.github.guillermodubon.invoward.extraction.infrastructure.ai.gemini;

import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

/** Creates Spring AI converters and JSON schemas for provider response contracts. */
public final class GeminiStructuredOutputConverters {

    private GeminiStructuredOutputConverters() {
    }

    public static BeanOutputConverter<GeminiStructuredOutput.Classification> classification() {
        return new BeanOutputConverter<>(GeminiStructuredOutput.Classification.class);
    }

    public static BeanOutputConverter<GeminiStructuredOutput.Extraction> extraction() {
        return new BeanOutputConverter<>(GeminiStructuredOutput.Extraction.class);
    }

    public static ExtractionDraft toDraft(GeminiStructuredOutput.Extraction output) {
        if (output == null) {
            return null;
        }
        List<ExtractionDraft.Line> lines = output.lines() == null ? null : output.lines().stream()
                .map(GeminiStructuredOutputConverters::toDraftLine)
                .toList();
        return new ExtractionDraft(
                output.vendorName(), output.documentNumber(), output.documentDate(), output.currency(),
                output.subtotal(), output.discountTotal(), output.taxTotal(), output.total(), lines);
    }

    private static ExtractionDraft.Line toDraftLine(GeminiStructuredOutput.Line line) {
        if (line == null) {
            return null;
        }
        GeminiStructuredOutput.BoundingBox box = line.boundingBox();
        ExtractionDraft.RawBoundingBox rawBox = box == null
                ? null
                : new ExtractionDraft.RawBoundingBox(box.xMin(), box.yMin(), box.xMax(), box.yMax());
        return new ExtractionDraft.Line(
                line.itemCode(), line.description(), line.quantity(), line.unit(), line.unitPrice(),
                line.discountAmount(), line.taxAmount(), line.lineTotal(), line.pageNumber(),
                line.sourceText(), rawBox);
    }
}
