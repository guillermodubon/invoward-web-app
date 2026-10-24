package io.github.guillermodubon.invoward.analysis.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A single private, versioned note attached to an Analysis. */
public record AnalysisNote(
        UUID analysisId,
        String text,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_TEXT_CODE_POINTS = 5_000;

    public AnalysisNote {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Analysis note text must not be blank");
        }
        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("Analysis note text exceeds the maximum length");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Analysis note version must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Analysis note updatedAt must not precede createdAt");
        }
    }

    public static AnalysisNote create(UUID analysisId, String text, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new AnalysisNote(analysisId, text, 0, now, now);
    }

    /** Returns the intended next state; the persistence adapter supplies the incremented @Version. */
    public AnalysisNote replaceText(String replacement, Instant changedAt) {
        Objects.requireNonNull(replacement, "replacement must not be null");
        Objects.requireNonNull(changedAt, "changedAt must not be null");
        if (text.equals(replacement)) {
            return this;
        }
        if (changedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Analysis note updatedAt must be monotonic");
        }
        return new AnalysisNote(analysisId, replacement, version, createdAt, changedAt);
    }
}
