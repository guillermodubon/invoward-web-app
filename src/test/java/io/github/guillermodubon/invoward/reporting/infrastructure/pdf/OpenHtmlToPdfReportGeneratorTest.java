package io.github.guillermodubon.invoward.reporting.infrastructure.pdf;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportGenerationException;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDiscrepancySnapshot;
import io.github.guillermodubon.invoward.reporting.application.model.ReportLineSnapshot;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenHtmlToPdfReportGeneratorTest {

    private final OpenHtmlToPdfReportGenerator generator =
            new OpenHtmlToPdfReportGenerator(new ReconciliationReportHtmlRenderer());

    @Test
    void createsAValidA4PdfWithReportTextAndLatinAccents(@TempDir Path tempDirectory) throws Exception {
        Path destination = tempDirectory.resolve("synthetic-report.pdf");

        generator.generate(snapshot("Compañía Ágil — México"), destination);

        assertTrue(Files.isRegularFile(destination));
        assertTrue(Files.size(destination) > 0);
        assertPdfHeader(destination);
        try (var pdf = Loader.loadPDF(destination.toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            var pageSize = pdf.getPage(0).getMediaBox();
            assertTrue(Math.abs(pageSize.getWidth() - 595.28f) < 2.0f);
            assertTrue(Math.abs(pageSize.getHeight() - 841.89f) < 2.0f);

            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("InvoWard Reconciliation Report"));
            assertTrue(text.contains("Compañía Ágil — México"));
            assertTrue(text.contains("2026-10-14 12:00 UTC"));
            assertNotNull(pdf.getDocumentCatalog());
            assertNull(pdf.getDocumentCatalog().getOpenAction());
            assertNull(pdf.getDocumentCatalog().getAcroForm());
            assertTrue(pdf.getPage(0).getAnnotations().isEmpty());
        }
    }

    @Test
    void externalFileResourcesAreDeniedBeforeResolution(@TempDir Path tempDirectory) throws Exception {
        Path localImage = tempDirectory.resolve("must-not-be-embedded.png");
        BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(pixel, "png", localImage.toFile()));

        String html = "<html><head><style>@page { size: A4; }</style></head><body>Safe text"
                + "<img src=\"" + localImage.toUri().toASCIIString() + "\"/></body></html>";
        Path destination = tempDirectory.resolve("resource-isolation.pdf");

        generator.renderHtmlToPdf(html, destination);

        assertPdfHeader(destination);
        try (var pdf = Loader.loadPDF(destination.toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            var resources = pdf.getPage(0).getResources();
            assertNotNull(resources);
            assertFalse(resources.getXObjectNames().iterator().hasNext());
            assertTrue(new PDFTextStripper().getText(pdf).contains("Safe text"));
        }
    }

    @Test
    void rendersAllReportSectionsAndLongUntrustedTextAcrossPagesWithoutActiveOrPrivateMetadata(
            @TempDir Path tempDirectory) throws Exception {
        Path destination = tempDirectory.resolve("complete-report.pdf");
        String longDescription = "LONG-DESCRIPTION-BEGIN "
                + "service details that must wrap across pages ".repeat(90)
                + " LONG-DESCRIPTION-END";
        String longResolutionNote = "LONG-NOTE-BEGIN "
                + "reviewed and approved after supplier confirmation ".repeat(80)
                + " LONG-NOTE-END";
        ReportSnapshot completeSnapshot = new ReportSnapshot(
                "Compañía <script>unsafe()</script> & Co.",
                "INV-SECURITY-42",
                "REF-SECURITY-19",
                "PURCHASE_ORDER",
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-09-28"),
                "USD",
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                new BigDecimal("120.0000"),
                new BigDecimal("125.0000"),
                new BigDecimal("5.0000"),
                new BigDecimal("3"),
                new BigDecimal("5.0000"),
                true,
                new ReportSnapshot.Summary(3, 1, 0, 1, 1, 0, 0, 1),
                List.of(
                        new ReportLineSnapshot(
                                ReconciliationLineStatus.MATCHED,
                                "REF-LONG", longDescription, "INV-LONG", "Invoice service description",
                                new BigDecimal("1.0000"), new BigDecimal("1.0000"), BigDecimal.ZERO,
                                new BigDecimal("120.0000"), new BigDecimal("125.0000"), new BigDecimal("5.0000"),
                                new BigDecimal("120.0000"), new BigDecimal("125.0000"), new BigDecimal("5.0000")),
                        new ReportLineSnapshot(
                                ReconciliationLineStatus.MISSING,
                                "REF-MISSING", "Reference-only item", null, null,
                                BigDecimal.ONE, null, null, new BigDecimal("4.0000"), null, null,
                                new BigDecimal("4.0000"), null, null),
                        new ReportLineSnapshot(
                                ReconciliationLineStatus.UNEXPECTED,
                                null, null, "INV-UNEXPECTED", "Invoice-only item",
                                null, BigDecimal.ONE, null, null, new BigDecimal("8.0000"), null,
                                null, new BigDecimal("8.0000"), null)),
                List.of(new ReportDiscrepancySnapshot(
                        DiscrepancyType.TOTAL_MISMATCH,
                        "120.0000",
                        "125.0000",
                        new BigDecimal("5.0000"),
                        new BigDecimal("4.166667"),
                        "The invoice total differs from the reference total.",
                        DiscrepancyResolutionStatus.RESOLVED,
                        DiscrepancyResolutionReason.OTHER,
                        longResolutionNote)),
                Instant.parse("2026-10-14T12:00:00Z"));

        generator.generate(completeSnapshot, destination);

        try (var pdf = Loader.loadPDF(destination.toFile())) {
            assertTrue(pdf.getNumberOfPages() > 1, "long descriptions and notes should paginate");
            String text = new PDFTextStripper().getText(pdf);
            String normalizedText = text.replaceAll("\\s+", "");
            for (String expected : List.of(
                    "InvoWard Reconciliation Report", "Compañía <script>unsafe()</script> & Co.",
                    "PURCHASE_ORDER", "REF-SECURITY-19", "2026-09-28", "INV-SECURITY-42", "2026-10-01", "USD",
                    "REVIEW REQUIRED", "Matched lines", "Different lines", "Missing lines", "Unexpected lines",
                    "Open discrepancies", "Accepted discrepancies", "Resolved discrepancies", "3%", "5.0000",
                    "120.0000", "125.0000", "A possible duplicate invoice was detected.", "TOTAL_MISMATCH",
                    "The invoice total differs from the reference total.", "RESOLVED", "OTHER", "REF-MISSING",
                    "INV-UNEXPECTED", "LONG-DESCRIPTION-BEGIN", "LONG-DESCRIPTION-END", "LONG-NOTE-BEGIN",
                    "LONG-NOTE-END", "2026-10-14 12:00 UTC")) {
                assertTrue(normalizedText.contains(expected.replaceAll("\\s+", "")),
                        "PDF text should contain: " + expected);
            }
            assertFalse(text.contains("private-owner-id"));
            assertFalse(text.contains("private-guest-session-id"));
            assertFalse(text.contains("private-storage-key"));
            assertFalse(text.contains("private-sha256"));
            assertFalse(text.contains("private-signed-url"));

            var documentInformation = pdf.getDocumentInformation();
            String pdfMetadata = documentInformation.getTitle() + " " + documentInformation.getAuthor() + " "
                    + documentInformation.getSubject() + " " + documentInformation.getKeywords();
            assertFalse(pdfMetadata.contains("Compañía"));
            assertFalse(pdfMetadata.contains("INV-SECURITY-42"));
            assertFalse(pdfMetadata.contains("REF-SECURITY-19"));
            assertFalse(pdfMetadata.contains("private-owner-id"));
            assertFalse(pdfMetadata.contains("private-storage-key"));

            assertNull(pdf.getDocumentCatalog().getOpenAction());
            assertNull(pdf.getDocumentCatalog().getAcroForm());
            assertNull(pdf.getDocumentCatalog().getNames(), "PDF must not contain embedded files");
            pdf.getPages().forEach(page -> {
                try {
                    assertTrue(page.getAnnotations().isEmpty(), "PDF must not contain links or annotations");
                } catch (IOException exception) {
                    throw new AssertionError("Could not inspect PDF page annotations", exception);
                }
            });
        }
    }

    @Test
    void reportsRendererFailuresWithAProviderNeutralSafeMessage(@TempDir Path tempDirectory) {
        Path missingParentDestination = tempDirectory.resolve("missing-parent").resolve("report.pdf");

        ReportGenerationException exception = assertThrows(
                ReportGenerationException.class,
                () -> generator.generate(snapshot("Supplier"), missingParentDestination));

        assertEquals("Report generation failed", exception.getMessage());
        assertFalse(exception.getMessage().contains(missingParentDestination.toString()));
    }

    private static void assertPdfHeader(Path destination) throws IOException {
        byte[] header = Files.readAllBytes(destination);
        assertTrue(header.length >= 5);
        assertEquals('%', (char) header[0]);
        assertEquals('P', (char) header[1]);
        assertEquals('D', (char) header[2]);
        assertEquals('F', (char) header[3]);
        assertEquals('-', (char) header[4]);
    }

    private static ReportSnapshot snapshot(String supplierName) {
        return new ReportSnapshot(
                supplierName,
                "INV-2026-001",
                "REF-2026-001",
                "QUOTE",
                null,
                null,
                "USD",
                AnalysisReconciliationStatus.MATCHED,
                null,
                null,
                null,
                null,
                null,
                false,
                new ReportSnapshot.Summary(0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                List.of(),
                Instant.parse("2026-10-14T12:00:00Z"));
    }
}
