package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Safe application read projection for a persisted line-item match set. */
public record LineItemMatchSetView(
        UUID analysisId,
        AnalysisStatus analysisStatus,
        boolean reviewRequired,
        List<MatchEntry> matches) {

    public LineItemMatchSetView {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(analysisStatus, "analysisStatus must not be null");
        matches = List.copyOf(matches);
    }

    public record MatchEntry(
            UUID id,
            LineMatchStatus status,
            LineMatchMethod method,
            BigDecimal confidence,
            long version,
            Instant reviewedAt,
            LineItem reference,
            LineItem invoice) {

        public MatchEntry {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(method, "method must not be null");
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
        }
    }

    /** Display-only line data; extraction evidence and persistence metadata are intentionally omitted. */
    public record LineItem(
            UUID lineItemId,
            int position,
            String itemCode,
            String description,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {

        public LineItem {
            Objects.requireNonNull(lineItemId, "lineItemId must not be null");
            Objects.requireNonNull(description, "description must not be null");
            if (position < 0) {
                throw new IllegalArgumentException("position must not be negative");
            }
        }
    }
}
