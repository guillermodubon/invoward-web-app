package io.github.guillermodubon.invoward.reporting.infrastructure.pdf;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportGenerationException;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;
import io.github.guillermodubon.invoward.reporting.application.port.ReportGenerator;
import io.github.guillermodubon.invoward.reporting.infrastructure.config.ReportingProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfReportArtifactGeneratorTest {

    @Test
    void validatesPdfComputesExactShaAndDeletesTheOpaqueTemporaryFile(@TempDir Path temporaryDirectory)
            throws Exception {
        PdfReportArtifactGenerator generator = generator(
                PdfReportArtifactGeneratorTest::writeValidPdf,
                temporaryDirectory,
                DataSize.ofMegabytes(20));

        GeneratedReportArtifact artifact = generator.generate(snapshot("PRIVATE-SUPPLIER-SENTINEL"));
        Path generatedFile = artifact.temporaryFile();

        assertTrue(Files.isRegularFile(generatedFile));
        assertTrue(generatedFile.getFileName().toString().startsWith("invoward-report-"));
        assertFalse(generatedFile.getFileName().toString().contains("PRIVATE-SUPPLIER-SENTINEL"));
        assertEquals(Files.size(generatedFile), artifact.sizeBytes());
        assertEquals(sha256(Files.readAllBytes(generatedFile)), artifact.sha256());
        assertTrue(artifact.sha256().matches("[0-9a-f]{64}"));
        try (var pdf = Loader.loadPDF(generatedFile.toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
        }

        artifact.close();
        artifact.close();

        assertFalse(Files.exists(generatedFile));
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void rejectsAndCleansEmptyOutput(@TempDir Path temporaryDirectory) {
        assertRejectedAndCleaned(temporaryDirectory, (snapshot, destination) -> writeBytes(destination, new byte[0]));
    }

    @Test
    void rejectsAndCleansNonPdfOutput(@TempDir Path temporaryDirectory) {
        assertRejectedAndCleaned(temporaryDirectory, (snapshot, destination) ->
                writeBytes(destination, "not a PDF".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void rejectsAndCleansCorruptOrTruncatedPdf(@TempDir Path temporaryDirectory) {
        assertRejectedAndCleaned(temporaryDirectory, (snapshot, destination) ->
                writeBytes(destination, "%PDF-1.7\ntruncated".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void rejectsAndCleansPdfWithoutPages(@TempDir Path temporaryDirectory) {
        assertRejectedAndCleaned(temporaryDirectory, (snapshot, destination) -> {
            try (PDDocument document = new PDDocument()) {
                document.save(destination.toFile());
            } catch (IOException exception) {
                throw new ReportGenerationException(exception);
            }
        });
    }

    @Test
    void rejectsAndCleansPdfLargerThanConfiguredMaximum(@TempDir Path temporaryDirectory) {
        assertRejectedAndCleaned(
                temporaryDirectory,
                PdfReportArtifactGeneratorTest::writeValidPdf,
                DataSize.ofBytes(32));
    }

    @Test
    void cleansTemporaryFileWhenRendererFails(@TempDir Path temporaryDirectory) {
        ReportGenerator renderer = (snapshot, destination) -> {
            try {
                Files.writeString(destination, "partial", StandardCharsets.US_ASCII);
            } catch (IOException exception) {
                throw new ReportGenerationException(exception);
            }
            throw new ReportGenerationException(new IOException("synthetic render failure"));
        };

        ReportGenerationException exception = assertThrows(
                ReportGenerationException.class,
                () -> generator(renderer, temporaryDirectory, DataSize.ofMegabytes(20)).generate(snapshot("Supplier")));

        assertEquals("Report generation failed", exception.getMessage());
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void cleansTemporaryFileWhenRendererThrowsAnError(@TempDir Path temporaryDirectory) {
        ReportGenerator renderer = (snapshot, destination) -> {
            writeBytes(destination, "partial".getBytes(StandardCharsets.US_ASCII));
            throw new AssertionError("synthetic fatal rendering failure");
        };

        assertThrows(
                AssertionError.class,
                () -> generator(renderer, temporaryDirectory, DataSize.ofMegabytes(20)).generate(snapshot("Supplier")));

        assertDirectoryEmpty(temporaryDirectory);
    }

    private static void assertRejectedAndCleaned(Path temporaryDirectory, ReportGenerator renderer) {
        assertRejectedAndCleaned(temporaryDirectory, renderer, DataSize.ofMegabytes(20));
    }

    private static void assertRejectedAndCleaned(
            Path temporaryDirectory,
            ReportGenerator renderer,
            DataSize maxSize) {
        ReportGenerationException exception = assertThrows(
                ReportGenerationException.class,
                () -> generator(renderer, temporaryDirectory, maxSize).generate(snapshot("Supplier")));

        assertEquals("Report generation failed", exception.getMessage());
        assertDirectoryEmpty(temporaryDirectory);
    }

    private static PdfReportArtifactGenerator generator(
            ReportGenerator renderer,
            Path temporaryDirectory,
            DataSize maxSize) {
        ReportingProperties properties = new ReportingProperties(
                new ReportingProperties.Pdf(maxSize),
                new ReportingProperties.Csv(DataSize.ofMegabytes(20)),
                Duration.ofMinutes(5));
        return new PdfReportArtifactGenerator(renderer, properties, temporaryDirectory);
    }

    private static void writeValidPdf(ReportSnapshot snapshot, Path destination) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 750);
                content.showText("Synthetic report");
                content.endText();
            }
            document.save(destination.toFile());
        } catch (IOException exception) {
            throw new ReportGenerationException(exception);
        }
    }

    private static void writeBytes(Path destination, byte[] bytes) {
        try {
            Files.write(destination, bytes);
        } catch (IOException exception) {
            throw new ReportGenerationException(exception);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertDirectoryEmpty(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        } catch (IOException exception) {
            throw new AssertionError("Could not inspect temporary test directory", exception);
        }
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
