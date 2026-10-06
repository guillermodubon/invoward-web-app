package io.github.guillermodubon.invoward.extraction.infrastructure.ai.gemini;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.application.service.ProviderOutputValidator;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in live smoke test for the production Gemini adapter; normal CI never calls Google. */
@SpringBootTest(properties = "spring.ai.model.chat=google-genai")
@EnabledIfEnvironmentVariable(named = "GEMINI_LIVE_TEST", matches = "(?i)true")
class GeminiDocumentIntelligenceLiveIT {

    private static final EnumSet<DocumentType> REFERENCE_TYPES = EnumSet.of(
            DocumentType.QUOTE, DocumentType.ESTIMATE, DocumentType.PURCHASE_ORDER);

    @TempDir
    Path temporaryDirectory;

    @Autowired
    SpringAiGeminiDocumentIntelligence intelligence;

    @Autowired
    ProviderOutputValidator outputValidator;

    @DynamicPropertySource
    static void configureTestInfrastructure(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
        registry.add("spring.ai.google.genai.api-key", () -> requiredEnvironmentVariable("GEMINI_API_KEY"));
        registry.add("spring.ai.google.genai.chat.model", () -> requiredEnvironmentVariable("GEMINI_MODEL"));
    }

    @Test
    void classifiesAndExtractsSyntheticReferenceAndInvoiceDocuments() throws Exception {
        DocumentIntelligenceInput referenceInput = input(
                DocumentRole.REFERENCE,
                syntheticPdf(List.of(
                        "QUOTATION",
                        "Vendor: Acme Industrial Supplies",
                        "Quote number: Q-2026-1001",
                        "Date: 2026-09-14",
                        "Currency: USD",
                        "Item: Steel fastener kit",
                        "Quantity: 2 boxes",
                        "Unit price: 25.00",
                        "Line total: 50.00",
                        "Subtotal: 50.00",
                        "Tax: 5.00",
                        "Total: 55.00")));
        DocumentIntelligenceInput invoiceInput = input(
                DocumentRole.INVOICE,
                syntheticPdf(List.of(
                        "INVOICE",
                        "Vendor: Acme Industrial Supplies",
                        "Invoice number: INV-2026-1002",
                        "Date: 2026-09-15",
                        "Currency: USD",
                        "Item: Copper wire spool",
                        "Quantity: 3 units",
                        "Unit price: 12.00",
                        "Line total: 36.00",
                        "Subtotal: 36.00",
                        "Tax: 3.60",
                        "Total: 39.60")));

        DocumentType referenceType = intelligence.classify(referenceInput).detectedType();
        DocumentType invoiceType = intelligence.classify(invoiceInput).detectedType();
        assertTrue(REFERENCE_TYPES.contains(referenceType), "synthetic quotation should classify as a reference type");
        assertEquals(DocumentType.INVOICE, invoiceType);

        ExtractionDraft referenceDraft = intelligence.extract(referenceInput, referenceType);
        ExtractionDraft invoiceDraft = intelligence.extract(invoiceInput, invoiceType);
        ExtractedDocument validatedReference = outputValidator.validate(referenceDraft, referenceInput.pageCount());
        ExtractedDocument validatedInvoice = outputValidator.validate(invoiceDraft, invoiceInput.pageCount());

        assertBroadlyValid(validatedReference);
        assertBroadlyValid(validatedInvoice);
    }

    private DocumentIntelligenceInput input(DocumentRole role, byte[] pdf) throws IOException {
        Path path = Files.createTempFile(temporaryDirectory, "gemini-live-", ".pdf");
        Files.write(path, pdf);
        return new DocumentIntelligenceInput(UUID.randomUUID(), role, "application/pdf", 1, path);
    }

    private static byte[] syntheticPdf(List<String> lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(48, 740);
                for (String line : lines) {
                    content.showText(line);
                    content.newLineAtOffset(0, -18);
                }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void assertBroadlyValid(ExtractedDocument document) {
        assertNotNull(document.vendorName());
        assertFalse(document.vendorName().isBlank());
        assertEquals("USD", document.currency());
        assertFalse(document.lines().isEmpty());
        assertNotNull(document.lines().getFirst().description());
        assertFalse(document.lines().getFirst().description().isBlank());
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when GEMINI_LIVE_TEST=true");
        }
        return value;
    }
}
