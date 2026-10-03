package io.github.guillermodubon.invoward.document.infrastructure.upload;

import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException.Failure;
import io.github.guillermodubon.invoward.document.application.model.DocumentFileFormat;
import io.github.guillermodubon.invoward.document.application.model.IncomingDocumentUpload;
import io.github.guillermodubon.invoward.document.application.model.ValidatedDocumentUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentUploadValidator;
import io.github.guillermodubon.invoward.document.infrastructure.config.DocumentUploadProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Stages uploads with bounded memory and a temporary filename unrelated to user input. */
@Component
public final class SafeDocumentUploadValidator implements DocumentUploadValidator {

    private static final int COPY_BUFFER_SIZE = 8 * 1024;
    private static final int MAX_FILENAME_CODE_POINTS = 255;
    private static final String TEMP_FILE_PREFIX = "invoward-document-";
    private static final String TEMP_FILE_SUFFIX = ".upload";

    private final DocumentUploadProperties properties;
    private final Path temporaryDirectory;
    private final PdfDocumentValidator pdfDocumentValidator;
    private final ImageDocumentValidator imageDocumentValidator;

    @Autowired
    public SafeDocumentUploadValidator(
            DocumentUploadProperties properties,
            PdfDocumentValidator pdfDocumentValidator,
            ImageDocumentValidator imageDocumentValidator) {
        this(properties, Path.of(System.getProperty("java.io.tmpdir")), pdfDocumentValidator, imageDocumentValidator);
    }

    SafeDocumentUploadValidator(
            DocumentUploadProperties properties,
            Path temporaryDirectory,
            PdfDocumentValidator pdfDocumentValidator,
            ImageDocumentValidator imageDocumentValidator) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory must not be null");
        this.pdfDocumentValidator = Objects.requireNonNull(pdfDocumentValidator, "pdfDocumentValidator must not be null");
        this.imageDocumentValidator = Objects.requireNonNull(
                imageDocumentValidator, "imageDocumentValidator must not be null");
    }

    @Override
    public ValidatedDocumentUpload validate(IncomingDocumentUpload incoming) {
        Objects.requireNonNull(incoming, "incoming must not be null");
        Path stagedFile = null;
        ValidatedDocumentUpload validated;
        try (InputStream input = incoming.content()) {
            String filename = sanitizeFilename(incoming.originalFilename());
            DocumentFileFormat format = resolveFormat(filename, incoming.reportedContentType());

            Files.createDirectories(temporaryDirectory);
            stagedFile = Files.createTempFile(temporaryDirectory, TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
            CopyResult copyResult = stageBounded(input, stagedFile, properties.maxFileSize().toBytes());
            int pageCount;
            if (format == DocumentFileFormat.PDF) {
                pageCount = pdfDocumentValidator.validate(stagedFile, properties.maxPdfPages());
            } else {
                if (pdfDocumentValidator.hasPdfHeader(stagedFile)) {
                    throw new DocumentUploadValidationException(Failure.TYPE_MISMATCH);
                }
                pageCount = imageDocumentValidator.validate(
                        stagedFile, format, properties.maxImageWidth(), properties.maxImageHeight());
            }

            validated = ValidatedDocumentUpload.staged(
                    stagedFile, filename, format, copyResult.sizeBytes(), pageCount, copyResult.sha256());
        } catch (DocumentUploadValidationException exception) {
            deleteAfterFailure(stagedFile, exception);
            throw exception;
        } catch (IOException exception) {
            deleteAfterFailure(stagedFile, exception);
            throw new UncheckedIOException("Document could not be staged", exception);
        } catch (RuntimeException exception) {
            deleteAfterFailure(stagedFile, exception);
            throw exception;
        }
        stagedFile = null;
        return validated;
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null) {
            throw new DocumentUploadValidationException(Failure.INVALID_FILENAME);
        }
        int basenameStart = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1;
        String basename = stripUnicodeWhitespace(filename.substring(basenameStart));
        if (basename.isEmpty() || basename.codePointCount(0, basename.length()) > MAX_FILENAME_CODE_POINTS) {
            throw new DocumentUploadValidationException(Failure.INVALID_FILENAME);
        }
        boolean invalidCharacter = basename.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) || isUnpairedSurrogate(codePoint));
        if (invalidCharacter) {
            throw new DocumentUploadValidationException(Failure.INVALID_FILENAME);
        }
        return basename;
    }

    private static String stripUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isUnpairedSurrogate(int codePoint) {
        return codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
    }

    private static DocumentFileFormat resolveFormat(String filename, String reportedContentType) {
        String extension = extensionOf(filename);
        DocumentFileFormat extensionFormat = DocumentFileFormat.fromExtension(extension);
        if (extensionFormat == null) {
            throw new DocumentUploadValidationException(Failure.UNSUPPORTED_TYPE);
        }

        String normalizedContentType = normalizeContentType(reportedContentType);
        DocumentFileFormat contentTypeFormat = DocumentFileFormat.fromContentType(normalizedContentType);
        if (contentTypeFormat == null) {
            throw new DocumentUploadValidationException(Failure.UNSUPPORTED_TYPE);
        }
        if (extensionFormat != contentTypeFormat) {
            throw new DocumentUploadValidationException(Failure.TYPE_MISMATCH);
        }
        return extensionFormat;
    }

    private static String extensionOf(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot < 0 ? null : filename.substring(lastDot).toLowerCase(Locale.ROOT);
    }

    private static String normalizeContentType(String reportedContentType) {
        if (reportedContentType == null || reportedContentType.isBlank()) {
            return null;
        }
        String normalizedValue = reportedContentType.strip();
        if (normalizedValue.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        int parameterStart = normalizedValue.indexOf(';');
        String mediaType = (parameterStart >= 0
                ? normalizedValue.substring(0, parameterStart)
                : normalizedValue).strip();
        try {
            MimeType mimeType = MimeTypeUtils.parseMimeType(mediaType);
            return (mimeType.getType() + "/" + mimeType.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (InvalidMimeTypeException exception) {
            return null;
        }
    }

    private static CopyResult stageBounded(InputStream input, Path stagedFile, long maxBytes) throws IOException {
        MessageDigest digest = sha256Digest();
        long sizeBytes = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(stagedFile, StandardOpenOption.WRITE)) {
            while (true) {
                long remaining = maxBytes - sizeBytes;
                int readLimit = (int) Math.min(buffer.length, remaining + 1);
                int read = input.read(buffer, 0, readLimit);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    int nextByte = input.read();
                    if (nextByte < 0) {
                        break;
                    }
                    if (sizeBytes == maxBytes) {
                        throw new DocumentUploadValidationException(Failure.TOO_LARGE);
                    }
                    output.write(nextByte);
                    digest.update((byte) nextByte);
                    sizeBytes++;
                    continue;
                }
                if (read > remaining) {
                    throw new DocumentUploadValidationException(Failure.TOO_LARGE);
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                sizeBytes += read;
            }
        }
        if (sizeBytes == 0) {
            throw new DocumentUploadValidationException(Failure.EMPTY_FILE);
        }
        return new CopyResult(sizeBytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void deleteAfterFailure(Path stagedFile, Exception originalFailure) {
        if (stagedFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(stagedFile);
        } catch (IOException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    private record CopyResult(long sizeBytes, String sha256) {
    }
}
