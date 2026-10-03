package io.github.guillermodubon.invoward.document.application.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** A bounded, staged upload. The caller owns it and must close it to delete its temporary file. */
public final class ValidatedDocumentUpload implements AutoCloseable {

    private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Path temporaryFile;
    private final String originalFilename;
    private final DocumentFileFormat fileFormat;
    private final long sizeBytes;
    private final int pageCount;
    private final String sha256;

    private ValidatedDocumentUpload(
            Path temporaryFile,
            String originalFilename,
            DocumentFileFormat fileFormat,
            long sizeBytes,
            int pageCount,
            String sha256) {
        this.temporaryFile = Objects.requireNonNull(temporaryFile, "temporaryFile must not be null");
        this.originalFilename = Objects.requireNonNull(originalFilename, "originalFilename must not be null");
        this.fileFormat = Objects.requireNonNull(fileFormat, "fileFormat must not be null");
        this.sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be greater than 0");
        }
        if (pageCount <= 0) {
            throw new IllegalArgumentException("pageCount must be greater than 0");
        }
        if (!SHA_256_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
        this.sizeBytes = sizeBytes;
        this.pageCount = pageCount;
    }

    public static ValidatedDocumentUpload staged(
            Path temporaryFile,
            String originalFilename,
            DocumentFileFormat fileFormat,
            long sizeBytes,
            int pageCount,
            String sha256) {
        return new ValidatedDocumentUpload(temporaryFile, originalFilename, fileFormat, sizeBytes, pageCount, sha256);
    }

    public Path temporaryFile() {
        return temporaryFile;
    }

    public String originalFilename() {
        return originalFilename;
    }

    public DocumentFileFormat fileFormat() {
        return fileFormat;
    }

    public String contentType() {
        return fileFormat.canonicalContentType();
    }

    public String canonicalExtension() {
        return fileFormat.canonicalExtension();
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    /** Returns the parser-verified page count (one for a validated image). */
    public int pageCount() {
        return pageCount;
    }

    public String sha256() {
        return sha256;
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(temporaryFile);
    }

    @Override
    public String toString() {
        return "ValidatedDocumentUpload[fileFormat=" + fileFormat + ", sizeBytes=" + sizeBytes + "]";
    }
}
