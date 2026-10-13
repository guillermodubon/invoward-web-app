package io.github.guillermodubon.invoward.reconciliation.api.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceCandidate;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceSignal;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Safe HTTP representation shared by reconciliation write and read endpoints. */
public record ReconciliationResponse(
        UUID analysisId,
        AnalysisStatus analysisStatus,
        AnalysisReconciliationStatus reconciliationStatus,
        BigDecimal priceTolerancePercent,
        BigDecimal priceToleranceAbsolute,
        BigDecimal referenceTotal,
        BigDecimal invoiceTotal,
        BigDecimal difference,
        DuplicateInvoice duplicateInvoice,
        Summary summary,
        List<ReconciliationLineResponse> lines,
        List<DiscrepancyResponse> discrepancies) {

    public ReconciliationResponse {
        lines = List.copyOf(lines);
        discrepancies = List.copyOf(discrepancies);
    }

    public static ReconciliationResponse from(ReconciliationResult result) {
        return new ReconciliationResponse(
                result.analysisId(),
                result.analysisStatus(),
                result.reconciliationStatus(),
                result.priceTolerance().priceTolerancePercent(),
                result.priceTolerance().priceToleranceAbsolute(),
                result.referenceTotal(),
                result.invoiceTotal(),
                result.difference(),
                DuplicateInvoice.from(result.duplicateInvoice()),
                Summary.from(result.summary()),
                result.lines().stream().map(ReconciliationLineResponse::from).toList(),
                result.discrepancies().stream().map(DiscrepancyResponse::from).toList());
    }

    public record DuplicateInvoice(boolean possibleDuplicate, List<Candidate> candidates) {
        public DuplicateInvoice {
            candidates = List.copyOf(candidates);
        }

        private static DuplicateInvoice from(
                io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning warning) {
            return new DuplicateInvoice(warning.possibleDuplicate(),
                    warning.candidates().stream().map(Candidate::from).toList());
        }
    }

    public record Candidate(
            UUID analysisId,
            Instant analyzedAt,
            String invoiceNumber,
            List<DuplicateInvoiceSignal> signals) {
        public Candidate {
            signals = List.copyOf(signals);
        }

        private static Candidate from(DuplicateInvoiceCandidate candidate) {
            return new Candidate(candidate.analysisId(), candidate.analyzedAt(),
                    candidate.invoiceNumber(), candidate.signals());
        }
    }

    public record Summary(
            long lineCount,
            long matchedCount,
            long differentCount,
            long missingCount,
            long unexpectedCount,
            long openDiscrepancyCount,
            long acceptedDiscrepancyCount,
            long resolvedDiscrepancyCount) {
        private static Summary from(ReconciliationSummary summary) {
            return new Summary(summary.lineCount(), summary.matchedCount(), summary.differentCount(),
                    summary.missingCount(), summary.unexpectedCount(), summary.openDiscrepancyCount(),
                    summary.acceptedDiscrepancyCount(), summary.resolvedDiscrepancyCount());
        }
    }
}
