package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatchingPlanTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void acceptsCompleteCoverIncludingUnmatchedRowsAndCopiesInputs() {
        MatchableLineItem referenceMatched = line(0);
        MatchableLineItem referenceUnmatched = line(1);
        MatchableLineItem invoiceMatched = line(0);
        MatchableLineItem invoiceUnmatched = line(1);
        List<LineItemMatch> matches = List.of(
                match(LineMatchStatus.MATCHED, referenceMatched.id(), invoiceMatched.id()),
                match(LineMatchStatus.UNMATCHED_REFERENCE, referenceUnmatched.id(), null),
                match(LineMatchStatus.UNMATCHED_INVOICE, null, invoiceUnmatched.id()));

        MatchingPlan plan = new MatchingPlan(ANALYSIS_ID,
                List.of(referenceMatched, referenceUnmatched), List.of(invoiceMatched, invoiceUnmatched), matches);

        assertEquals(2, plan.referenceLines().size());
        assertEquals(2, plan.invoiceLines().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.matches().clear());
    }

    @Test
    void rejectsMissingDuplicateForeignAndWrongAnalysisMatches() {
        MatchableLineItem reference = line(0);
        MatchableLineItem invoice = line(0);

        assertThrows(IllegalArgumentException.class, () -> new MatchingPlan(
                ANALYSIS_ID, List.of(reference), List.of(invoice), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MatchingPlan(
                ANALYSIS_ID, List.of(reference), List.of(invoice), List.of(
                        match(LineMatchStatus.MATCHED, reference.id(), invoice.id()),
                        match(LineMatchStatus.UNMATCHED_REFERENCE, reference.id(), null))));
        assertThrows(IllegalArgumentException.class, () -> new MatchingPlan(
                ANALYSIS_ID, List.of(reference), List.of(invoice), List.of(
                        match(LineMatchStatus.MATCHED, UUID.randomUUID(), invoice.id()))));
        assertThrows(IllegalArgumentException.class, () -> new MatchingPlan(
                ANALYSIS_ID, List.of(reference), List.of(invoice), List.of(
                        new LineItemMatch(UUID.randomUUID(), UUID.randomUUID(), reference.id(), invoice.id(),
                                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE, 0,
                                null, NOW, NOW))));
    }

    private static MatchableLineItem line(int position) {
        return new MatchableLineItem(UUID.randomUUID(), position, "SKU-" + position,
                "Consulting service " + position, "consulting service " + position, "hour");
    }

    private static LineItemMatch match(LineMatchStatus status, UUID referenceId, UUID invoiceId) {
        return new LineItemMatch(UUID.randomUUID(), ANALYSIS_ID, referenceId, invoiceId,
                status, LineMatchMethod.SKU, BigDecimal.ONE, 0, null, NOW, NOW);
    }
}
