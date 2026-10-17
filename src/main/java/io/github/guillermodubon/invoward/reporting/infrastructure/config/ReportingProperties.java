package io.github.guillermodubon.invoward.reporting.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "invoward.reporting")
public record ReportingProperties(
        @DefaultValue Pdf pdf,
        @DefaultValue Csv csv,
        @DefaultValue("5m") Duration downloadUrlTtl) {

    private static final DataSize MAX_OUTPUT_SIZE = DataSize.ofMegabytes(50);
    private static final Duration MIN_DOWNLOAD_URL_TTL = Duration.ofSeconds(30);
    private static final Duration MAX_DOWNLOAD_URL_TTL = Duration.ofMinutes(15);

    public ReportingProperties {
        Objects.requireNonNull(pdf, "PDF reporting properties must be configured");
        Objects.requireNonNull(csv, "CSV reporting properties must be configured");
        validateMaxSize(pdf.maxSize(), "REPORT_PDF_MAX_SIZE");
        validateMaxSize(csv.maxSize(), "REPORT_CSV_MAX_SIZE");
        if (downloadUrlTtl == null
                || downloadUrlTtl.compareTo(MIN_DOWNLOAD_URL_TTL) < 0
                || downloadUrlTtl.compareTo(MAX_DOWNLOAD_URL_TTL) > 0) {
            throw new IllegalArgumentException(
                    "REPORT_DOWNLOAD_URL_TTL must be between 30s and 15m");
        }
    }

    private static void validateMaxSize(DataSize maxSize, String propertyName) {
        Objects.requireNonNull(maxSize, propertyName + " must be configured");
        if (maxSize.toBytes() <= 0 || maxSize.compareTo(MAX_OUTPUT_SIZE) > 0) {
            throw new IllegalArgumentException(
                    propertyName + " must be greater than 0 and at most " + MAX_OUTPUT_SIZE);
        }
    }

    public record Pdf(@DefaultValue("20MB") DataSize maxSize) {
    }

    public record Csv(@DefaultValue("20MB") DataSize maxSize) {
    }
}
