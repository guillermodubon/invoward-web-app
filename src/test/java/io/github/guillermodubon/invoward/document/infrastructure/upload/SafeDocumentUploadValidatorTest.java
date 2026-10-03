package io.github.guillermodubon.invoward.document.infrastructure.upload;

import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException.Failure;
import io.github.guillermodubon.invoward.document.application.model.DocumentFileFormat;
import io.github.guillermodubon.invoward.document.application.model.IncomingDocumentUpload;
import io.github.guillermodubon.invoward.document.application.model.ValidatedDocumentUpload;
import io.github.guillermodubon.invoward.document.infrastructure.config.DocumentUploadProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeDocumentUploadValidatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void stagesBoundedBytesAndComputesExactSha256() throws Exception {
        byte[] content = imageWithDimensions("png", 1, 1);
        TrackingInputStream input = new TrackingInputStream(content);

        try (ValidatedDocumentUpload upload = validator(content.length).validate(incoming(
                "invoice.PNG", " IMAGE/PNG; charset=binary ", content.length, input))) {
            assertTrue(Files.exists(upload.temporaryFile()));
            assertEquals(temporaryDirectory, upload.temporaryFile().getParent());
            assertTrue(upload.temporaryFile().getFileName().toString().startsWith("invoward-document-"));
            assertFalse(upload.temporaryFile().getFileName().toString().contains("invoice"));
            assertEquals("invoice.PNG", upload.originalFilename());
            assertEquals(DocumentFileFormat.PNG, upload.fileFormat());
            assertEquals("image/png", upload.contentType());
            assertEquals(".png", upload.canonicalExtension());
            assertEquals(1, upload.pageCount());
            assertEquals(content.length, upload.sizeBytes());
            assertEquals(content.length, Files.size(upload.temporaryFile()));
            assertEquals(sha256(content), upload.sha256());
            assertTrue(Arrays.equals(content, Files.readAllBytes(upload.temporaryFile())));
        }

        assertTrue(input.closed());
        assertEquals(0, stagedFileCount());
    }

    @Test
    void acceptsTheExactConfiguredByteLimit() throws Exception {
        byte[] content = imageWithDimensions("png", 2, 2);

        try (ValidatedDocumentUpload upload = validator(content.length).validate(incoming(
                "scan.png", "IMAGE/PNG; charset=binary", content.length, content))) {
            assertEquals(content.length, upload.sizeBytes());
            assertEquals("image/png", upload.contentType());
            assertEquals(".png", upload.canonicalExtension());
        }
    }

    @Test
    void rejectsActualBytesOverLimitEvenWhenReportedSizeIsSmallerAndStopsAfterFirstExcessByte() {
        byte[] content = new byte[100];
        TrackingInputStream input = new TrackingInputStream(content);
        IncomingDocumentUpload incoming = new IncomingDocumentUpload("invoice.pdf", "application/pdf", 1, input);

        DocumentUploadValidationException exception = assertThrows(
                DocumentUploadValidationException.class, () -> validator(4).validate(incoming));

        assertEquals(Failure.TOO_LARGE, exception.failure());
        assertEquals(5, input.bytesRead());
        assertTrue(input.closed());
        assertEquals(0, stagedFileCount());
    }

    @Test
    void rejectsEmptyFileAndDeletesItsStagingFile() {
        TrackingInputStream input = new TrackingInputStream(new byte[0]);

        DocumentUploadValidationException exception = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(4).validate(new IncomingDocumentUpload("empty.pdf", "application/pdf", 0, input)));

        assertEquals(Failure.EMPTY_FILE, exception.failure());
        assertTrue(input.closed());
        assertEquals(0, stagedFileCount());
    }

    @Test
    void sanitizesUnixAndWindowsPathComponentsWithoutUsingThemForTempFiles() throws Exception {
        String[] untrustedPaths = {"../../invoices/factura.pdf", "C:\\private\\invoices\\factura.pdf"};
        byte[] content = imageWithDimensions("jpeg", 1, 1);

        for (String path : untrustedPaths) {
            String imagePath = path.substring(0, path.lastIndexOf('.')) + ".jpeg";
            try (ValidatedDocumentUpload upload = validator(content.length).validate(incoming(
                    imagePath, "image/jpeg", content.length, content))) {
                assertEquals("factura.jpeg", upload.originalFilename());
                assertEquals(temporaryDirectory, upload.temporaryFile().getParent());
                assertNotEquals(imagePath, upload.temporaryFile().toString());
            }
        }
        assertEquals(0, stagedFileCount());
    }

    @Test
    void stripsUnicodeWhitespaceButPreservesValidUnicodeInFilename() throws Exception {
        byte[] content = imageWithDimensions("png", 1, 1);
        try (ValidatedDocumentUpload upload = validator(content.length).validate(incoming(
                "\\\u00a0factura-ñ-😀.PNG\u00a0", "image/png", content.length, content))) {
            assertEquals("factura-ñ-😀.PNG", upload.originalFilename());
            assertEquals(DocumentFileFormat.PNG, upload.fileFormat());
        }
    }

    @Test
    void acceptsFilenameAtThe255UnicodeCodePointBoundary() throws Exception {
        byte[] content = pdfWithPages(1, false);
        String filename = "😀".repeat(251) + ".pdf";

        try (ValidatedDocumentUpload upload = validator(content.length).validate(incoming(
                filename, "application/pdf", content.length, content))) {
            assertEquals(255, upload.originalFilename().codePointCount(0, upload.originalFilename().length()));
            assertEquals(filename, upload.originalFilename());
        }
        assertEquals(0, stagedFileCount());
    }

    @Test
    void rejectsControlCharactersAndNamesLongerThan255UnicodeCodePoints() {
        for (String invalidFilename : Arrays.asList(
                "bad\u0000.pdf", "bad\r.pdf", "bad\n.pdf", "bad\u0001.pdf", "😀".repeat(256) + ".pdf")) {
            DocumentUploadValidationException exception = assertThrows(
                    DocumentUploadValidationException.class,
                    () -> validator(8).validate(incoming(
                            invalidFilename, "application/pdf", 1, new byte[] {1})));
            assertEquals(Failure.INVALID_FILENAME, exception.failure());
        }
        assertEquals(0, stagedFileCount());
    }

    @Test
    void rejectsUnsupportedExtensionsAndMimeTypes() {
        assertFailure("invoice.gif", "image/png", Failure.UNSUPPORTED_TYPE);
        assertFailure("invoice.jpg", "image/jpg", Failure.UNSUPPORTED_TYPE);
        assertFailure("invoice.pdf", "application/octet-stream", Failure.UNSUPPORTED_TYPE);
    }

    @Test
    void rejectsExtensionAndDeclaredMimeMismatch() {
        assertFailure("invoice.pdf", "image/jpeg", Failure.TYPE_MISMATCH);
    }

    @Test
    void rejectsMissingFilenameOrDeclaredMimeSafely() {
        assertFailure(null, "application/pdf", Failure.INVALID_FILENAME);
        assertFailure("", "application/pdf", Failure.INVALID_FILENAME);
        assertFailure("  \u00a0 ", "application/pdf", Failure.INVALID_FILENAME);
        assertFailure("invoice.pdf", null, Failure.UNSUPPORTED_TYPE);
        assertFailure("invoice.pdf", "application/pdf\r\nX-Test: value", Failure.UNSUPPORTED_TYPE);
    }

    @Test
    void deletesStagingFileWhenIncomingStreamFailsDuringCopy() {
        FailingInputStream input = new FailingInputStream();
        IncomingDocumentUpload incoming = new IncomingDocumentUpload(
                "invoice.png", "image/png", 4, input);

        assertThrows(UncheckedIOException.class, () -> validator(8).validate(incoming));

        assertTrue(input.closed());
        assertEquals(0, stagedFileCount());
    }

    @Test
    void closesIncomingStreamWhenMetadataIsRejected() {
        TrackingInputStream input = new TrackingInputStream(new byte[] {1});
        IncomingDocumentUpload upload = new IncomingDocumentUpload("invoice.exe", "application/pdf", 1, input);

        assertThrows(DocumentUploadValidationException.class, () -> validator(8).validate(upload));
        assertTrue(input.closed());
        assertEquals(0, stagedFileCount());
    }

    @Test
    void validatedUploadCloseIsIdempotentAndDeletesTheTemporaryFile() throws Exception {
        byte[] content = imageWithDimensions("png", 1, 1);
        ValidatedDocumentUpload upload = validator(content.length).validate(incoming(
                "invoice.png", "image/png", content.length, content));
        Path path = upload.temporaryFile();
        assertTrue(Files.exists(path));

        upload.close();
        upload.close();

        assertFalse(Files.exists(path));
        assertEquals(0, stagedFileCount());
    }

    @Test
    void acceptsParserValidatedPdfAndRetainsItsActualPageCount() throws Exception {
        byte[] content = pdfWithPages(1, false);

        try (ValidatedDocumentUpload upload = validator(1024 * 1024).validate(incoming(
                "invoice.pdf", "application/pdf", content.length, content))) {
            assertEquals(DocumentFileFormat.PDF, upload.fileFormat());
            assertEquals("application/pdf", upload.contentType());
            assertEquals(".pdf", upload.canonicalExtension());
            assertEquals(1, upload.pageCount());
        }

        assertEquals(0, stagedFileCount());
    }

    @Test
    void acceptsFifteenPdfPagesAndRejectsSixteen() throws Exception {
        byte[] fifteenPages = pdfWithPages(15, false);
        try (ValidatedDocumentUpload upload = validator(1024 * 1024).validate(incoming(
                "reference.pdf", "application/pdf", fifteenPages.length, fifteenPages))) {
            assertEquals(15, upload.pageCount());
        }

        byte[] sixteenPages = pdfWithPages(16, false);
        DocumentUploadValidationException exception = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(1024 * 1024).validate(incoming(
                        "reference.pdf", "application/pdf", sixteenPages.length, sixteenPages)));
        assertEquals(Failure.PDF_PAGE_LIMIT_EXCEEDED, exception.failure());
        assertEquals(0, stagedFileCount());
    }

    @Test
    void rejectsAValidPdfWithNoPages() throws Exception {
        byte[] content = pdfWithPages(0, false);

        DocumentUploadValidationException exception = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(1024 * 1024).validate(incoming(
                        "empty-document.pdf", "application/pdf", content.length, content)));

        assertEquals(Failure.DOCUMENT_INVALID, exception.failure());
        assertEquals(0, stagedFileCount());
    }

    @Test
    void rejectsMissingMagicAndCorruptOrTruncatedPdfAndCleansStagingFiles() {
        byte[][] invalidPdfs = {
                "not a pdf".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                "%PDF-1.7\n1 0 obj".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        };

        for (byte[] content : invalidPdfs) {
            DocumentUploadValidationException exception = assertThrows(
                    DocumentUploadValidationException.class,
                    () -> validator(1024 * 1024).validate(incoming(
                            "invalid.pdf", "application/pdf", content.length, content)));
            assertEquals(Failure.DOCUMENT_INVALID, exception.failure());
            assertEquals(0, stagedFileCount());
        }
    }

    @Test
    void rejectsEncryptedPdfWithoutExposingParserDetails() throws Exception {
        byte[] content = pdfWithPages(1, true);

        DocumentUploadValidationException exception = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(1024 * 1024).validate(incoming(
                        "protected.pdf", "application/pdf", content.length, content)));

        assertEquals(Failure.PDF_PASSWORD_PROTECTED, exception.failure());
        assertFalse(exception.getMessage().contains("user-test-password"));
        assertEquals(0, stagedFileCount());
    }

    @Test
    void rejectsPdfBytesDeclaredAsPngAndImageBytesDeclaredAsPdf() throws Exception {
        byte[] pdf = pdfWithPages(1, false);
        DocumentUploadValidationException pdfAsPng = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(1024 * 1024).validate(incoming(
                        "image.png", "image/png", pdf.length, pdf)));
        assertEquals(Failure.TYPE_MISMATCH, pdfAsPng.failure());

        byte[] pngHeader = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        DocumentUploadValidationException pngAsPdf = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(1024 * 1024).validate(incoming(
                        "document.pdf", "application/pdf", pngHeader.length, pngHeader)));
        assertEquals(Failure.DOCUMENT_INVALID, pngAsPdf.failure());
        assertEquals(0, stagedFileCount());
    }

    @Test
    void acceptsJpegWithCanonicalMetadataAndOnePage() throws Exception {
        byte[] content = imageWithDimensions("jpeg", 4, 3);

        try (ValidatedDocumentUpload upload = validator(content.length).validate(incoming(
                "photo.JPEG", "image/jpeg", content.length, content))) {
            assertEquals(DocumentFileFormat.JPEG, upload.fileFormat());
            assertEquals("image/jpeg", upload.contentType());
            assertEquals(".jpg", upload.canonicalExtension());
            assertEquals(1, upload.pageCount());
        }
    }

    @Test
    void rejectsCorruptAndTruncatedImagesWithMatchingExtensionsAndMimeTypes() {
        byte[][] corruptImages = {
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10},
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
                "not an image".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        };
        String[] filenames = {"image.png", "image.jpg", "image.png"};
        String[] contentTypes = {"image/png", "image/jpeg", "image/png"};

        for (int index = 0; index < corruptImages.length; index++) {
            byte[] content = corruptImages[index];
            String filename = filenames[index];
            String contentType = contentTypes[index];
            DocumentUploadValidationException exception = assertThrows(
                    DocumentUploadValidationException.class,
                    () -> validator(1024 * 1024).validate(incoming(
                            filename, contentType, content.length, content)));
            assertEquals(Failure.DOCUMENT_INVALID, exception.failure());
            assertEquals(0, stagedFileCount());
        }
    }

    @Test
    void rejectsImageMagicThatDisagreesWithDeclaredJpegOrPng() throws Exception {
        byte[] png = imageWithDimensions("png", 2, 2);
        DocumentUploadValidationException pngAsJpeg = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(png.length).validate(incoming(
                        "image.jpg", "image/jpeg", png.length, png)));
        assertEquals(Failure.TYPE_MISMATCH, pngAsJpeg.failure());

        byte[] jpeg = imageWithDimensions("jpeg", 2, 2);
        DocumentUploadValidationException jpegAsPng = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(jpeg.length).validate(incoming(
                        "image.png", "image/png", jpeg.length, jpeg)));
        assertEquals(Failure.TYPE_MISMATCH, jpegAsPng.failure());
        assertEquals(0, stagedFileCount());
    }

    @Test
    void acceptsConfiguredImageDimensionBoundariesUsingSubsampledDecode() throws Exception {
        byte[] maximumWidth = imageWithDimensions("png", 6000, 1);
        try (ValidatedDocumentUpload upload = validator(maximumWidth.length).validate(incoming(
                "wide.png", "image/png", maximumWidth.length, maximumWidth))) {
            assertEquals(1, upload.pageCount());
        }

        byte[] maximumHeight = imageWithDimensions("png", 1, 6000);
        try (ValidatedDocumentUpload upload = validator(maximumHeight.length).validate(incoming(
                "tall.png", "image/png", maximumHeight.length, maximumHeight))) {
            assertEquals(1, upload.pageCount());
        }
        assertEquals(0, stagedFileCount());
    }

    @Test
    void rejectsImagesAboveEitherConfiguredDimensionLimit() throws Exception {
        byte[] tooWide = imageWithDimensions("png", 6001, 1);
        DocumentUploadValidationException widthException = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(tooWide.length).validate(incoming(
                        "wide.png", "image/png", tooWide.length, tooWide)));
        assertEquals(Failure.IMAGE_DIMENSIONS_EXCEEDED, widthException.failure());

        byte[] tooTall = imageWithDimensions("png", 1, 6001);
        DocumentUploadValidationException heightException = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(tooTall.length).validate(incoming(
                        "tall.png", "image/png", tooTall.length, tooTall)));
        assertEquals(Failure.IMAGE_DIMENSIONS_EXCEEDED, heightException.failure());
        assertEquals(0, stagedFileCount());
    }

    private void assertFailure(String filename, String contentType, Failure expectedFailure) {
        DocumentUploadValidationException exception = assertThrows(
                DocumentUploadValidationException.class,
                () -> validator(8).validate(incoming(filename, contentType, 1, new byte[] {1})));
        assertEquals(expectedFailure, exception.failure());
    }

    private SafeDocumentUploadValidator validator(long maxBytes) {
        DocumentUploadProperties properties = new DocumentUploadProperties(
                DataSize.ofBytes(maxBytes), DataSize.ofMegabytes(20), 15, 6000, 6000);
        return new SafeDocumentUploadValidator(
                properties, temporaryDirectory, new PdfDocumentValidator(), new ImageDocumentValidator());
    }

    private static byte[] imageWithDimensions(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new AssertionError("No ImageIO writer available for " + format);
            }
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static byte[] pdfWithPages(int pageCount, boolean encrypted) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page = 0; page < pageCount; page++) {
                document.addPage(new PDPage());
            }
            if (encrypted) {
                document.protect(new StandardProtectionPolicy(
                        "owner-test-password", "user-test-password", new AccessPermission()));
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static IncomingDocumentUpload incoming(
            String filename, String contentType, long reportedSize, byte[] content) {
        return new IncomingDocumentUpload(filename, contentType, reportedSize, new ByteArrayInputStream(content));
    }

    private static IncomingDocumentUpload incoming(
            String filename, String contentType, long reportedSize, TrackingInputStream content) {
        return new IncomingDocumentUpload(filename, contentType, reportedSize, content);
    }

    private long stagedFileCount() {
        try (var files = Files.list(temporaryDirectory)) {
            return files.count();
        } catch (IOException exception) {
            throw new AssertionError("Could not inspect the isolated staging directory", exception);
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private int bytesRead;
        private boolean closed;

        private TrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                bytesRead += read;
            }
            return read;
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private int bytesRead() {
            return bytesRead;
        }

        private boolean closed() {
            return closed;
        }
    }

    private static final class FailingInputStream extends InputStream {

        private int readCalls;
        private boolean closed;

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (readCalls++ > 0) {
                throw new IOException("simulated input failure");
            }
            int copied = Math.min(4, length);
            Arrays.fill(bytes, offset, offset + copied, (byte) 1);
            return copied;
        }

        @Override
        public int read() throws IOException {
            throw new IOException("simulated input failure");
        }

        @Override
        public void close() {
            closed = true;
        }

        private boolean closed() {
            return closed;
        }
    }
}
