package io.github.guillermodubon.invoward.reporting.infrastructure.pdf;

import io.github.guillermodubon.invoward.reporting.application.exception.ReportGenerationException;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;
import io.github.guillermodubon.invoward.reporting.application.port.ReportArtifactGenerator;
import io.github.guillermodubon.invoward.reporting.application.port.ReportGenerator;
import io.github.guillermodubon.invoward.reporting.infrastructure.config.ReportingProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Creates and validates a bounded PDF before transferring temporary-file ownership to the caller. */
@Component
public final class PdfReportArtifactGenerator implements ReportArtifactGenerator {

    private static final String TEMP_FILE_PREFIX = "invoward-report-";
    private static final String TEMP_FILE_SUFFIX = ".pdf";
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int HASH_BUFFER_SIZE = 8 * 1024;

    private final ReportGenerator reportGenerator;
    private final long maximumSizeBytes;
    private final Path temporaryDirectory;

    @Autowired
    public PdfReportArtifactGenerator(ReportGenerator reportGenerator, ReportingProperties properties) {
        this(reportGenerator, properties, null);
    }

    PdfReportArtifactGenerator(
            ReportGenerator reportGenerator,
            ReportingProperties properties,
            Path temporaryDirectory) {
        this.reportGenerator = Objects.requireNonNull(reportGenerator, "reportGenerator must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.maximumSizeBytes = properties.pdf().maxSize().toBytes();
        this.temporaryDirectory = temporaryDirectory;
    }

    @Override
    public GeneratedReportArtifact generate(ReportSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        Path temporaryFile;
        try {
            temporaryFile = createTemporaryFile();
        } catch (IOException exception) {
            throw new ReportGenerationException(exception);
        }

        boolean ownershipTransferred = false;
        Throwable primaryFailure = null;
        try {
            reportGenerator.generate(snapshot, temporaryFile);
            long sizeBytes = validatePdf(temporaryFile);
            String sha256 = calculateSha256(temporaryFile);
            GeneratedReportArtifact artifact = GeneratedReportArtifact.validated(temporaryFile, sizeBytes, sha256);
            ownershipTransferred = true;
            return artifact;
        } catch (ReportGenerationException exception) {
            primaryFailure = exception;
            throw exception;
        } catch (IOException | RuntimeException exception) {
            ReportGenerationException failure = new ReportGenerationException(exception);
            primaryFailure = failure;
            throw failure;
        } catch (Error error) {
            primaryFailure = error;
            throw error;
        } finally {
            if (!ownershipTransferred) {
                deleteAfterFailure(temporaryFile, primaryFailure);
            }
        }
    }

    private Path createTemporaryFile() throws IOException {
        if (temporaryDirectory == null) {
            return Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
        }
        return Files.createTempFile(temporaryDirectory, TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
    }

    private long validatePdf(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw invalidPdf();
        }

        long sizeBytes = Files.size(file);
        if (sizeBytes <= 0 || sizeBytes > maximumSizeBytes || !hasPdfHeader(file)) {
            throw invalidPdf();
        }

        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            if (pdf.getNumberOfPages() < 1) {
                throw invalidPdf();
            }
        } catch (IOException | RuntimeException exception) {
            throw invalidPdf();
        }
        return sizeBytes;
    }

    private static boolean hasPdfHeader(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return java.util.Arrays.equals(input.readNBytes(PDF_HEADER.length), PDF_HEADER);
        }
    }

    private static String calculateSha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ReportGenerationException(exception);
        }

        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            byte[] buffer = new byte[HASH_BUFFER_SIZE];
            while (input.read(buffer) != -1) {
                // Stream the exact validated file without retaining its contents in memory.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteAfterFailure(Path file, Throwable failure) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException cleanupFailure) {
            ReportGenerationException safeCleanupFailure = new ReportGenerationException(cleanupFailure);
            if (failure != null) {
                failure.addSuppressed(safeCleanupFailure);
            } else {
                throw safeCleanupFailure;
            }
        }
    }

    private static ReportGenerationException invalidPdf() {
        return new ReportGenerationException(new IOException("Generated PDF validation failed"));
    }
}
