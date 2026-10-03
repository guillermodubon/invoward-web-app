package io.github.guillermodubon.invoward.document.infrastructure.upload;

import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException.Failure;
import io.github.guillermodubon.invoward.document.application.model.DocumentFileFormat;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;

/** Validates supported raster images with a bounded decoded image size. */
@Component
public final class ImageDocumentValidator {

    private static final byte[] PNG_HEADER = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] JPEG_HEADER = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final int MAX_DECODED_DIMENSION = 512;

    public int validate(Path stagedFile, DocumentFileFormat expectedFormat, int maxWidth, int maxHeight) {
        Objects.requireNonNull(stagedFile, "stagedFile must not be null");
        Objects.requireNonNull(expectedFormat, "expectedFormat must not be null");
        if (expectedFormat != DocumentFileFormat.JPEG && expectedFormat != DocumentFileFormat.PNG) {
            throw new IllegalArgumentException("expectedFormat must be JPEG or PNG");
        }
        if (maxWidth <= 0 || maxHeight <= 0) {
            throw new IllegalArgumentException("image dimensions must be greater than 0");
        }

        DocumentFileFormat signatureFormat = detectFormat(stagedFile);
        if (signatureFormat == null) {
            throw invalidDocument();
        }
        if (signatureFormat != expectedFormat) {
            throw new DocumentUploadValidationException(Failure.TYPE_MISMATCH);
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(stagedFile.toFile())) {
            if (input == null) {
                throw invalidDocument();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidDocument();
            }
            return validateWithReader(readers.next(), input, expectedFormat, maxWidth, maxHeight);
        } catch (DocumentUploadValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidDocument();
        }
    }

    private static int validateWithReader(
            ImageReader reader,
            ImageInputStream input,
            DocumentFileFormat expectedFormat,
            int maxWidth,
            int maxHeight) {
        try {
            reader.setInput(input, false, true);
            if (!readerFormatMatches(reader.getFormatName(), expectedFormat)) {
                throw new DocumentUploadValidationException(Failure.TYPE_MISMATCH);
            }

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0) {
                throw invalidDocument();
            }
            if (width > maxWidth || height > maxHeight) {
                throw new DocumentUploadValidationException(Failure.IMAGE_DIMENSIONS_EXCEEDED);
            }

            ImageReadParam readParameters = reader.getDefaultReadParam();
            readParameters.setSourceSubsampling(
                    samplingFactor(width), samplingFactor(height), 0, 0);
            BufferedImage decoded = reader.read(0, readParameters);
            if (decoded == null) {
                throw invalidDocument();
            }
            try {
                if (decoded.getWidth() > MAX_DECODED_DIMENSION
                        || decoded.getHeight() > MAX_DECODED_DIMENSION) {
                    throw invalidDocument();
                }
            } finally {
                decoded.flush();
            }
            return 1;
        } catch (DocumentUploadValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidDocument();
        } finally {
            reader.dispose();
        }
    }

    private static DocumentFileFormat detectFormat(Path stagedFile) {
        try (InputStream input = Files.newInputStream(stagedFile)) {
            byte[] prefix = input.readNBytes(PNG_HEADER.length);
            if (Arrays.equals(prefix, PNG_HEADER)) {
                return DocumentFileFormat.PNG;
            }
            if (startsWith(prefix, JPEG_HEADER)) {
                return DocumentFileFormat.JPEG;
            }
            if (prefix.length >= 5
                    && prefix[0] == '%'
                    && prefix[1] == 'P'
                    && prefix[2] == 'D'
                    && prefix[3] == 'F'
                    && prefix[4] == '-') {
                return DocumentFileFormat.PDF;
            }
            return null;
        } catch (IOException exception) {
            throw invalidDocument();
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean readerFormatMatches(String readerFormat, DocumentFileFormat expectedFormat) {
        String format = readerFormat.toLowerCase(Locale.ROOT);
        return switch (expectedFormat) {
            case JPEG -> format.equals("jpeg") || format.equals("jpg");
            case PNG -> format.equals("png");
            case PDF -> false;
        };
    }

    private static int samplingFactor(int dimension) {
        return Math.max(1, (dimension + MAX_DECODED_DIMENSION - 1) / MAX_DECODED_DIMENSION);
    }

    private static DocumentUploadValidationException invalidDocument() {
        return new DocumentUploadValidationException(Failure.DOCUMENT_INVALID);
    }
}
