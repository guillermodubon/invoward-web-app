package io.github.guillermodubon.invoward.reporting.application.model;

import io.github.guillermodubon.invoward.reporting.application.exception.ReportGenerationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** A validated PDF held in a temporary file that its caller must close after use. */
public final class GeneratedReportArtifact implements AutoCloseable {

    private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Path temporaryFile;
    private final long sizeBytes;
    private final String sha256;

    private GeneratedReportArtifact(Path temporaryFile, long sizeBytes, String sha256) {
        this.temporaryFile = Objects.requireNonNull(temporaryFile, "temporaryFile must not be null");
        this.sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be greater than 0");
        }
        if (!SHA_256_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
        this.sizeBytes = sizeBytes;
    }

    public static GeneratedReportArtifact validated(Path temporaryFile, long sizeBytes, String sha256) {
        return new GeneratedReportArtifact(temporaryFile, sizeBytes, sha256);
    }

    public Path temporaryFile() {
        return temporaryFile;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String sha256() {
        return sha256;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException exception) {
            throw new ReportGenerationException(exception);
        }
    }

    @Override
    public String toString() {
        return "GeneratedReportArtifact[sizeBytes=" + sizeBytes + "]";
    }
}
