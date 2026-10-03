package io.github.guillermodubon.invoward.document.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.util.Objects;

@ConfigurationProperties(prefix = "invoward.documents")
public record DocumentUploadProperties(
        @DefaultValue("10MB") DataSize maxFileSize,
        @DefaultValue("20MB") DataSize maxCombinedSize,
        @DefaultValue("15") int maxPdfPages,
        @DefaultValue("6000") int maxImageWidth,
        @DefaultValue("6000") int maxImageHeight) {

    private static final DataSize MAX_FILE_SIZE = DataSize.ofMegabytes(10);
    private static final DataSize MAX_COMBINED_SIZE = DataSize.ofMegabytes(20);
    private static final int MAX_PDF_PAGES = 15;
    private static final int MAX_IMAGE_DIMENSION = 6000;

    public DocumentUploadProperties {
        requirePositiveAtMost(maxFileSize, MAX_FILE_SIZE, "DOCUMENT_MAX_FILE_SIZE");
        requirePositiveAtMost(maxCombinedSize, MAX_COMBINED_SIZE, "DOCUMENT_MAX_COMBINED_SIZE");
        requirePositiveAtMost(maxPdfPages, MAX_PDF_PAGES, "DOCUMENT_MAX_PDF_PAGES");
        requirePositiveAtMost(maxImageWidth, MAX_IMAGE_DIMENSION, "DOCUMENT_MAX_IMAGE_WIDTH");
        requirePositiveAtMost(maxImageHeight, MAX_IMAGE_DIMENSION, "DOCUMENT_MAX_IMAGE_HEIGHT");
    }

    private static void requirePositiveAtMost(DataSize actual, DataSize maximum, String property) {
        Objects.requireNonNull(actual, property + " must be configured");
        if (actual.toBytes() <= 0 || actual.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(property + " must be greater than 0 and at most " + maximum);
        }
    }

    private static void requirePositiveAtMost(int actual, int maximum, String property) {
        if (actual <= 0 || actual > maximum) {
            throw new IllegalArgumentException(property + " must be greater than 0 and at most " + maximum);
        }
    }
}
