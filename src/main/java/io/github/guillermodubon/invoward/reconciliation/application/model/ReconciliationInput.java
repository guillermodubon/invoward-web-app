package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.ConfirmedEvidenceSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable, validated snapshot of the confirmed source data consumed by the reconciliation engine. */
public record ReconciliationInput(
        Analysis analysis,
        Document referenceDocument,
        Document invoiceDocument,
        Map<UUID, ExtractedLineItem> referenceLines,
        Map<UUID, ExtractedLineItem> invoiceLines,
        List<LineItemMatch> matches,
        PriceTolerance priceTolerance,
        List<MatchSnapshot> matchSnapshots,
        ConfirmedEvidenceSource referenceSource,
        ConfirmedEvidenceSource invoiceSource) {

    public ReconciliationInput {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(referenceDocument, "referenceDocument must not be null");
        Objects.requireNonNull(invoiceDocument, "invoiceDocument must not be null");
        if (!analysis.id().equals(referenceDocument.analysisId())
                || !analysis.id().equals(invoiceDocument.analysisId())) {
            throw new IllegalArgumentException("source documents must belong to the input Analysis");
        }
        referenceLines = immutableMap(referenceLines, "referenceLines");
        invoiceLines = immutableMap(invoiceLines, "invoiceLines");
        matches = List.copyOf(matches);
        Objects.requireNonNull(priceTolerance, "priceTolerance must not be null");
        matchSnapshots = List.copyOf(matchSnapshots);
        Objects.requireNonNull(referenceSource, "referenceSource must not be null");
        Objects.requireNonNull(invoiceSource, "invoiceSource must not be null");
        if (!referenceDocument.equals(referenceSource.document())
                || !invoiceDocument.equals(invoiceSource.document())) {
            throw new IllegalArgumentException("evidence sources must match the input documents");
        }
        if (!priceTolerance.equals(analysis.priceTolerance())) {
            throw new IllegalArgumentException("input tolerance must match the Analysis tolerance");
        }
        if (!snapshot(matches).equals(matchSnapshots)) {
            throw new IllegalArgumentException("match snapshots must correspond exactly to the input matches");
        }
    }

    /** Captures the exact persisted match revision and line references for transaction-time stale checks. */
    public static List<MatchSnapshot> snapshot(List<LineItemMatch> matches) {
        return matches.stream().map(MatchSnapshot::from).toList();
    }

    private static Map<UUID, ExtractedLineItem> immutableMap(
            Map<UUID, ExtractedLineItem> source, String fieldName) {
        Objects.requireNonNull(source, fieldName + " must not be null");
        LinkedHashMap<UUID, ExtractedLineItem> copy = new LinkedHashMap<>();
        source.forEach((id, line) -> copy.put(
                Objects.requireNonNull(id, fieldName + " must not contain null ids"),
                Objects.requireNonNull(line, fieldName + " must not contain null lines")));
        return Collections.unmodifiableMap(copy);
    }

    public record MatchSnapshot(
            UUID matchId,
            long version,
            LineMatchStatus status,
            UUID referenceLineItemId,
            UUID invoiceLineItemId) {

        public MatchSnapshot {
            Objects.requireNonNull(matchId, "matchId must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
        }

        private static MatchSnapshot from(LineItemMatch match) {
            return new MatchSnapshot(match.id(), match.version(), match.status(),
                    match.referenceLineItemId(), match.invoiceLineItemId());
        }
    }
}
