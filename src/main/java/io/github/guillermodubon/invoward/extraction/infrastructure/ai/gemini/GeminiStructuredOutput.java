package io.github.guillermodubon.invoward.extraction.infrastructure.ai.gemini;

import io.github.guillermodubon.invoward.document.domain.DocumentType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider response shapes for Gemini structured output. Values remain untrusted until validated. */
public final class GeminiStructuredOutput {

    private GeminiStructuredOutput() {
    }

    public record Classification(DocumentType detectedType) {
    }

    public record Extraction(
            String vendorName,
            String documentNumber,
            String documentDate,
            String currency,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            List<Line> lines) {

        public Extraction {
            lines = lines == null ? null : Collections.unmodifiableList(new ArrayList<>(lines));
        }
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
            BoundingBox boundingBox) {
    }

    public record BoundingBox(Double xMin, Double yMin, Double xMax, Double yMax) {
    }
}
