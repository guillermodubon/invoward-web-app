package io.github.guillermodubon.invoward.reporting.application.model;

import java.util.Objects;

/** Complete UTF-8 CSV payload; bytes are defensively copied and never exposed through toString. */
public final class CsvExport {

    private final byte[] content;

    public CsvExport(byte[] content) {
        this.content = Objects.requireNonNull(content, "content must not be null").clone();
    }

    public byte[] content() {
        return content.clone();
    }

    public long sizeBytes() {
        return content.length;
    }

    @Override
    public String toString() {
        return "CsvExport[sizeBytes=" + content.length + "]";
    }
}
