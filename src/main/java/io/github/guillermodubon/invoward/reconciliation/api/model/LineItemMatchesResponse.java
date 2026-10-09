package io.github.guillermodubon.invoward.reconciliation.api.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchSetView;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Safe public representation of an owner's persisted line-item match set. */
public record LineItemMatchesResponse(
        UUID analysisId,
        AnalysisStatus analysisStatus,
        boolean reviewRequired,
        List<Match> matches) {

    public LineItemMatchesResponse {
        matches = List.copyOf(matches);
    }

    public static LineItemMatchesResponse from(LineItemMatchSetView view) {
        return new LineItemMatchesResponse(
                view.analysisId(),
                view.analysisStatus(),
                view.reviewRequired(),
                view.matches().stream().map(Match::from).toList());
    }

    public record Match(
            UUID id,
            LineMatchStatus status,
            LineMatchMethod method,
            BigDecimal confidence,
            long version,
            Instant reviewedAt,
            Line reference,
            Line invoice) {

        private static Match from(LineItemMatchSetView.MatchEntry entry) {
            return new Match(
                    entry.id(), entry.status(), entry.method(), entry.confidence(), entry.version(),
                    entry.reviewedAt(), Line.from(entry.reference()), Line.from(entry.invoice()));
        }
    }

    /** Display-only line projection; evidence and storage metadata are not exposed. */
    public record Line(
            UUID lineItemId,
            int position,
            String itemCode,
            String description,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {

        private static Line from(LineItemMatchSetView.LineItem line) {
            if (line == null) {
                return null;
            }
            return new Line(
                    line.lineItemId(), line.position(), line.itemCode(), line.description(),
                    line.quantity(), line.unit(), line.unitPrice(), line.lineTotal());
        }
    }
}
