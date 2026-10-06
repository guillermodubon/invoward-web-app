package io.github.guillermodubon.invoward.extraction.infrastructure.ai.gemini;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiStructuredOutputConvertersTest {

    @Test
    void classificationConverterGeneratesSchemaForEverySupportedType() {
        BeanOutputConverter<GeminiStructuredOutput.Classification> converter =
                GeminiStructuredOutputConverters.classification();

        assertThat(converter.getJsonSchema())
                .contains("detectedType")
                .contains("QUOTE")
                .contains("ESTIMATE")
                .contains("PURCHASE_ORDER")
                .contains("INVOICE")
                .contains("UNKNOWN");
    }

    @Test
    void extractionConverterGeneratesTheGenericHeaderLineAndEvidenceSchema() {
        BeanOutputConverter<GeminiStructuredOutput.Extraction> converter =
                GeminiStructuredOutputConverters.extraction();

        assertThat(converter.getJsonSchema())
                .contains("vendorName")
                .contains("documentNumber")
                .contains("documentDate")
                .contains("currency")
                .contains("subtotal")
                .contains("discountTotal")
                .contains("taxTotal")
                .contains("total")
                .contains("lines")
                .contains("itemCode")
                .contains("description")
                .contains("quantity")
                .contains("unit")
                .contains("unitPrice")
                .contains("discountAmount")
                .contains("taxAmount")
                .contains("lineTotal")
                .contains("pageNumber")
                .contains("sourceText")
                .contains("boundingBox")
                .contains("xMin")
                .contains("yMin")
                .contains("xMax")
                .contains("yMax");
    }
}
