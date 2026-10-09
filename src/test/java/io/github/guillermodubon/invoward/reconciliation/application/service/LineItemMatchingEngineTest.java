package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchCandidates;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchingResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.domain.LineDescriptionSimilarity;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemCodeNormalizer;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineItemMatchingEngineTest {

    private static final UUID ANALYSIS_ID = uuid(700);
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void matchesOnlyUniqueNormalizedSkuFirstAndPreservesIdentifierPunctuation() {
        MatchableLineItem reference = line(1, 0, " abc-123 ", "accepted reference item", "accepted reference item");
        MatchableLineItem invoice = line(2, 0, "ABC-123", "supplier label differs", "supplier label differs");

        LineItemMatchingResult result = engine().match(ANALYSIS_ID,
                List.of(reference), List.of(invoice), NOW, ids());

        LineItemMatch match = onlyMatch(result);
        assertEquals(LineMatchStatus.MATCHED, match.status());
        assertEquals(LineMatchMethod.SKU, match.method());
        assertEquals(new BigDecimal("1.0000"), match.confidence());
        assertTrue(result.ambiguousCandidates().isEmpty());
    }

    @Test
    void duplicateSkuFallsThroughToUniqueExactNormalizedDescriptions() {
        MatchableLineItem referenceAlpha = line(1, 0, "DUP-1", "alpha reference", "alpha normalized");
        MatchableLineItem referenceBeta = line(2, 1, "DUP-1", "beta reference", "beta normalized");
        MatchableLineItem invoiceAlpha = line(3, 0, "DUP-1", "alpha supplier", "alpha normalized");
        MatchableLineItem invoiceBeta = line(4, 1, null, "beta supplier", "beta normalized");

        LineItemMatchingResult result = engine().match(ANALYSIS_ID,
                List.of(referenceAlpha, referenceBeta), List.of(invoiceAlpha, invoiceBeta), NOW, ids());

        assertEquals(2, result.plan().matches().size());
        assertTrue(result.plan().matches().stream().allMatch(match ->
                match.status() == LineMatchStatus.MATCHED && match.method() == LineMatchMethod.NORMALIZED_NAME));
        assertEquals(List.of(referenceAlpha.id(), referenceBeta.id()), result.plan().matches().stream()
                .map(LineItemMatch::referenceLineItemId).toList());
    }

    @Test
    void removesEarlierPhaseLinesBeforeRunningLaterPhases() {
        MatchableLineItem skuReference = line(1, 0, "SKU-9", "first reference", "first normalized");
        MatchableLineItem nameReference = line(2, 1, null, "same reference", "same normalized");
        MatchableLineItem skuInvoice = line(3, 0, "SKU-9", "different invoice", "different normalized");
        MatchableLineItem nameInvoice = line(4, 1, null, "same invoice", "same normalized");

        LineItemMatchingResult result = engine().match(ANALYSIS_ID,
                List.of(nameReference, skuReference), List.of(nameInvoice, skuInvoice), NOW, ids());

        assertEquals(2, result.plan().matches().size());
        assertEquals(List.of(LineMatchMethod.SKU, LineMatchMethod.NORMALIZED_NAME),
                result.plan().matches().stream().map(LineItemMatch::method).toList());
        assertEquals(List.of(skuReference.id(), nameReference.id()),
                result.plan().matches().stream().map(LineItemMatch::referenceLineItemId).toList());
    }

    @Test
    void automaticallyMatchesMutualBestFuzzyPairWithRoundedDeterministicConfidence() {
        MatchableLineItem reference = line(1, 0, null, "reference",
                "industrial premium stainless steel bolt zinc plated large washer packaging");
        MatchableLineItem invoice = line(2, 0, null, "invoice",
                "industrial premium stainless steel bolts zinc plated large washer packaging");

        LineItemMatchingResult result = engine().match(ANALYSIS_ID,
                List.of(reference), List.of(invoice), NOW, ids());

        LineItemMatch match = onlyMatch(result);
        assertEquals(LineMatchStatus.MATCHED, match.status());
        assertEquals(LineMatchMethod.FUZZY, match.method());
        assertTrue(match.confidence().compareTo(new BigDecimal("0.9000")) >= 0);
        assertEquals(4, match.confidence().scale());
        assertNull(match.reviewedAt());
        assertTrue(result.ambiguousCandidates().isEmpty());
    }

    @Test
    void ambiguousMutualBestProducesReviewFallbackAndOrderedCandidateSet() {
        MatchableLineItem reference = line(1, 0, null, "reference", "blue paper archive storage box");
        MatchableLineItem invoiceLater = line(3, 1, null, "invoice later", "blue paper archive storage box");
        MatchableLineItem invoiceEarlier = line(2, 0, null, "invoice earlier", "blue paper archive storage box");

        LineItemMatchingResult result = engine().match(ANALYSIS_ID,
                List.of(reference), List.of(invoiceLater, invoiceEarlier), NOW, ids());

        LineItemMatch review = result.plan().matches().stream()
                .filter(match -> match.status() == LineMatchStatus.NEEDS_REVIEW).findFirst().orElseThrow();
        assertEquals(LineMatchMethod.FUZZY, review.method());
        assertEquals(new BigDecimal("1.0000"), review.confidence());
        assertEquals(invoiceEarlier.id(), review.invoiceLineItemId());
        assertEquals(1, result.ambiguousCandidates().size());
        assertEquals(List.of(invoiceEarlier.id(), invoiceLater.id()), result.ambiguousCandidates().getFirst()
                .candidates().stream().map(candidate -> candidate.lineItem().id()).toList());
    }

    @Test
    void completeCoverIncludesUnmatchedLinesAndExcludesWeakOrNonMutualPairsFromAiCandidates() {
        MatchableLineItem strongReference = line(1, 0, null, "ref", "metal bolt zinc plated washer large");
        MatchableLineItem competingReference = line(2, 1, null, "ref competitor", "metal bolt zinc plated washer black");
        MatchableLineItem unrelatedReference = line(3, 2, null, "unrelated ref", "citrus oranges from orchard");
        MatchableLineItem strongInvoice = line(4, 0, null, "invoice", "metal bolt zinc plated washer large premium");
        MatchableLineItem unrelatedInvoice = line(5, 1, null, "unrelated invoice", "annual cloud hosting subscription");

        LineItemMatchingResult result = engine().match(ANALYSIS_ID,
                List.of(unrelatedReference, competingReference, strongReference),
                List.of(unrelatedInvoice, strongInvoice), NOW, ids());

        assertEquals(3, result.plan().matches().stream()
                .filter(match -> match.referenceLineItemId() != null).count());
        assertEquals(2, result.plan().matches().stream()
                .filter(match -> match.invoiceLineItemId() != null).count());
        assertEquals(3, result.plan().matches().stream().filter(match ->
                match.status() == LineMatchStatus.NEEDS_REVIEW
                        || match.status() == LineMatchStatus.UNMATCHED_REFERENCE
                        || match.status() == LineMatchStatus.UNMATCHED_INVOICE).count());
        assertFalse(result.ambiguousCandidates().stream().anyMatch(candidate ->
                candidate.referenceLine().id().equals(competingReference.id())));
        assertTrue(result.plan().matches().stream().anyMatch(match ->
                match.status() == LineMatchStatus.UNMATCHED_REFERENCE
                        && match.referenceLineItemId().equals(unrelatedReference.id())));
        assertTrue(result.plan().matches().stream().anyMatch(match ->
                match.status() == LineMatchStatus.UNMATCHED_INVOICE
                        && match.invoiceLineItemId().equals(unrelatedInvoice.id())));
    }

    @Test
    void returnsCompleteEmptyPlanAndRejectsInvalidInputsOrNonUniqueGeneratedIds() {
        LineItemMatchingResult empty = engine().match(ANALYSIS_ID, List.of(), List.of(), NOW, ids());
        assertTrue(empty.plan().matches().isEmpty());

        MatchableLineItem same = line(1, 0, null, "one", "one normalized");
        assertThrows(IllegalArgumentException.class, () -> engine().match(
                ANALYSIS_ID, List.of(same, same), List.of(), NOW, ids()));
        MatchableLineItem second = line(2, 1, null, "two", "two normalized");
        MatchableLineItem secondInvoice = line(3, 1, null, "second", "two normalized");
        assertThrows(IllegalArgumentException.class, () -> engine().match(
                ANALYSIS_ID, List.of(same, second), List.of(same, secondInvoice), NOW, () -> uuid(900)));
    }

    private static LineItemMatchingEngine engine() {
        return new LineItemMatchingEngine(new LineItemCodeNormalizer(), new LineDescriptionSimilarity(),
                new BigDecimal("0.90"), new BigDecimal("0.65"), new BigDecimal("0.08"), 20);
    }

    private static MatchableLineItem line(int id, int position, String code, String description, String normalized) {
        return new MatchableLineItem(uuid(id), position, code, description, normalized, "unit");
    }

    private static LineItemMatch onlyMatch(LineItemMatchingResult result) {
        assertEquals(1, result.plan().matches().size());
        return result.plan().matches().getFirst();
    }

    private static java.util.function.Supplier<UUID> ids() {
        AtomicInteger next = new AtomicInteger(900);
        return () -> uuid(next.getAndIncrement());
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
