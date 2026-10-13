package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.EvidenceSide;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationPlanTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID LINE_ID = UUID.randomUUID();
    private static final UUID DISCREPANCY_ID = UUID.randomUUID();

    @Test
    void acceptsSignedDifferenceAndCopiesInputCollections() {
        List<ReconciliationLine> lines = new ArrayList<>(List.of(line()));
        ReconciliationPlan plan = new ReconciliationPlan(
                ANALYSIS_ID, lines, List.of(discrepancy(null)), List.of(evidence()), new BigDecimal("-12.5000"));
        lines.clear();
        assertEquals(1, plan.lines().size());
        assertEquals(new BigDecimal("-12.5000"), plan.analysisDifference());
        assertThrows(UnsupportedOperationException.class, () -> plan.lines().clear());
    }

    @Test
    void requiresUniqueAnalysisScopedRowsAndValidEvidenceReferences() {
        ReconciliationLine line = line();
        Discrepancy discrepancy = discrepancy(line.id());
        assertThrows(IllegalArgumentException.class,
                () -> new ReconciliationPlan(ANALYSIS_ID, List.of(line, line), List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconciliationPlan(ANALYSIS_ID, List.of(), List.of(discrepancy), List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconciliationPlan(ANALYSIS_ID, List.of(line), List.of(discrepancy(null)),
                        List.of(evidence(UUID.randomUUID())), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconciliationPlan(ANALYSIS_ID, List.of(line), List.of(), List.of(),
                        new BigDecimal("0.00001")));
    }

    @Test
    void requiresNewDiscrepanciesToStartOpenAtVersionZero() {
        Discrepancy accepted = new Discrepancy(DISCREPANCY_ID, ANALYSIS_ID, null,
                DiscrepancyType.TOTAL_MISMATCH, "100", "110", BigDecimal.TEN, null,
                "The totals differ.", DiscrepancyResolutionStatus.ACCEPTED,
                io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason.APPROVED_CHANGE,
                null, 1, NOW, NOW.minusSeconds(1), NOW);
        assertThrows(IllegalArgumentException.class,
                () -> new ReconciliationPlan(ANALYSIS_ID, List.of(), List.of(accepted), List.of(), null));
    }

    @Test
    void stringRepresentationContainsCountsButNotFinancialData() {
        String rendered = new ReconciliationPlan(
                ANALYSIS_ID, List.of(line()), List.of(discrepancy(null)), List.of(), new BigDecimal("12.5000"))
                .toString();
        assertTrue(!rendered.contains("12.5000"));
        assertTrue(rendered.contains("lineCount=1"));
    }

    private static ReconciliationLine line() {
        return new ReconciliationLine(LINE_ID, ANALYSIS_ID, UUID.randomUUID(), ReconciliationLineStatus.DIFFERENT,
                "REF", "INV", "Reference item", "Invoice item", null, null, null, null,
                null, null, null, null, null, NOW);
    }

    private static Discrepancy discrepancy(UUID reconciliationLineId) {
        return new Discrepancy(DISCREPANCY_ID, ANALYSIS_ID, reconciliationLineId,
                DiscrepancyType.TOTAL_MISMATCH, "100", "110", BigDecimal.TEN, null,
                "The totals differ.", DiscrepancyResolutionStatus.OPEN, null, null, 0, null, NOW, NOW);
    }

    private static DiscrepancyEvidence evidence() {
        return evidence(DISCREPANCY_ID);
    }

    private static DiscrepancyEvidence evidence(UUID discrepancyId) {
        return new DiscrepancyEvidence(UUID.randomUUID(), discrepancyId, UUID.randomUUID(),
                EvidenceSide.REFERENCE, 1, null, null, NOW);
    }
}
