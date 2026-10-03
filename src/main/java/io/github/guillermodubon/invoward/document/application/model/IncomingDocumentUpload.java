package io.github.guillermodubon.invoward.document.application.model;

import java.io.InputStream;
import java.util.Objects;

/** Untrusted document bytes and transport-reported metadata, independent of the HTTP stack. */
public record IncomingDocumentUpload(
        String originalFilename,
        String reportedContentType,
        long reportedSizeBytes,
        InputStream content) {

    public IncomingDocumentUpload {
        Objects.requireNonNull(content, "content must not be null");
    }

    @Override
    public String toString() {
        return "IncomingDocumentUpload[content=<stream>]";
    }
}
