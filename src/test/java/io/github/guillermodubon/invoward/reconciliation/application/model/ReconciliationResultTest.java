package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationResultTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();

    @Test
    void acceptsConsistentSummaryAndSignedDifference() {
        ReconciliationLine line = line(ReconciliationLineStatus.DIFFERENT);
        Discrepancy discrepancy = discrepancy();
        ReconciliationResult result = new ReconciliationResult(
                ANALYSIS_ID, AnalysisStatus.GENERATING_REPORT, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                PriceTolerance.exactMatch(), new BigDecimal("100.0000"), new BigDecimal("90.0000"),
                new BigDecimal("-10.0000"), DuplicateInvoiceWarning.none(),
                new ReconciliationSummary(1, 0, 1, 0, 0, 1, 0, 0),
                List.of(line), List.of(discrepancy));
        assertTrue(!result.toString().contains("-10.0000"));
    }

    @Test
    void rejectsSummaryThatDoesNotDescribeTheResultContents() {
        assertThrows(IllegalArgumentException.class, () -> new ReconciliationResult(
                ANALYSIS_ID, AnalysisStatus.GENERATING_REPORT, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                PriceTolerance.exactMatch(), null, null, null,
                DuplicateInvoiceWarning.none(),
                new ReconciliationSummary(1, 1, 0, 0, 0, 0, 0, 0),
                List.of(line(ReconciliationLineStatus.DIFFERENT)), List.of()));
    }

    @Test
    void rejectsRowsFromAnotherAnalysisAndIncompatibleAmounts() {
        assertThrows(IllegalArgumentException.class, () -> new ReconciliationResult(
                ANALYSIS_ID, AnalysisStatus.GENERATING_REPORT, AnalysisReconciliationStatus.MATCHED,
                PriceTolerance.exactMatch(), null, null, null,
                DuplicateInvoiceWarning.none(),
                new ReconciliationSummary(1, 1, 0, 0, 0, 0, 0, 0),
                List.of(lineFor(UUID.randomUUID(), ReconciliationLineStatus.MATCHED)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReconciliationResult(
                ANALYSIS_ID, AnalysisStatus.GENERATING_REPORT, AnalysisReconciliationStatus.MATCHED,
                PriceTolerance.exactMatch(), new BigDecimal("0.00001"), null, null,
                DuplicateInvoiceWarning.none(),
                new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0), List.of(), List.of()));
    }

    private static ReconciliationLine line(ReconciliationLineStatus status) {
        return lineFor(ANALYSIS_ID, status);
    }

    private static ReconciliationLine lineFor(UUID analysisId, ReconciliationLineStatus status) {
        return new ReconciliationLine(UUID.randomUUID(), analysisId, UUID.randomUUID(), status,
                null, null, "Reference", "Invoice", null, null, null, null, null, null,
                null, null, null, NOW);
    }

    private static Discrepancy discrepancy() {
        return new Discrepancy(UUID.randomUUID(), ANALYSIS_ID, null, DiscrepancyType.TOTAL_MISMATCH,
                "100", "90", BigDecimal.TEN, null, "The document totals differ.",
                DiscrepancyResolutionStatus.OPEN, null, null, 0, null, NOW, NOW);
    }
}
