package io.github.guillermodubon.invoward.document.application.model;

import java.util.Locale;
import java.util.Set;

/** Allowlisted original-file formats accepted by the document pipeline. */
public enum DocumentFileFormat {
    PDF("application/pdf", ".pdf", Set.of(".pdf")),
    JPEG("image/jpeg", ".jpg", Set.of(".jpg", ".jpeg")),
    PNG("image/png", ".png", Set.of(".png"));

    private final String canonicalContentType;
    private final String canonicalExtension;
    private final Set<String> acceptedExtensions;

    DocumentFileFormat(String canonicalContentType, String canonicalExtension, Set<String> acceptedExtensions) {
        this.canonicalContentType = canonicalContentType;
        this.canonicalExtension = canonicalExtension;
        this.acceptedExtensions = acceptedExtensions;
    }

    public String canonicalContentType() {
        return canonicalContentType;
    }

    public String canonicalExtension() {
        return canonicalExtension;
    }

    public static DocumentFileFormat fromExtension(String extension) {
        if (extension == null) {
            return null;
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        for (DocumentFileFormat format : values()) {
            if (format.acceptedExtensions.contains(normalized)) {
                return format;
            }
        }
        return null;
    }

    public static DocumentFileFormat fromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (DocumentFileFormat format : values()) {
            if (format.canonicalContentType.equals(contentType)) {
                return format;
            }
        }
        return null;
    }
}
