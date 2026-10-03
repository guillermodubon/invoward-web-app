package io.github.guillermodubon.invoward.document.application.model;

import io.github.guillermodubon.invoward.document.domain.Document;

import java.util.Objects;

/** Authorized document metadata paired with a short-lived download address. */
public record DocumentDownload(Document document, PresignedDownload download) {

    public DocumentDownload {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(download, "download must not be null");
    }

    @Override
    public String toString() {
        return "DocumentDownload[document=<metadata>, download=<redacted>]";
    }
}
