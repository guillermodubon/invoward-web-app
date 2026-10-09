package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchCandidates;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchingResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchingPlan;
import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.ai.FakeAmbiguousLineMatcher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmbiguousLineMatchingAssistantTest {

    private static final UUID ANALYSIS_ID = uuid(700);
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void validAiSuggestionBecomesReviewPairWithDeterministicSelectedPairConfidence() {
        MatchableLineItem reference = line(1, 0, "REF-SKU", "reference description");
        MatchableLineItem invoiceA = line(2, 0, "INV-A", "first candidate");
        MatchableLineItem invoiceB = line(3, 1, "INV-B", "second candidate");
        LineItemMatchingResult result = result(
                List.of(reference), List.of(invoiceA, invoiceB),
                List.of(group(reference, candidate(invoiceA, 0.81), candidate(invoiceB, 0.76))));
        FakeAmbiguousLineMatcher matcher = new FakeAmbiguousLineMatcher();
        matcher.configureCandidate("C2");

        MatchingPlan plan = assistant(matcher, 5).assist(result);

        LineItemMatch suggested = matchForReference(plan, reference.id());
        assertEquals(LineMatchStatus.NEEDS_REVIEW, suggested.status());
        assertEquals(LineMatchMethod.AI, suggested.method());
        assertEquals(invoiceB.id(), suggested.invoiceLineItemId());
        assertEquals(new BigDecimal("0.7600"), suggested.confidence());
        assertNull(suggested.reviewedAt());
        assertTrue(plan.matches().stream().anyMatch(match ->
                match.status() == LineMatchStatus.UNMATCHED_INVOICE
                        && match.invoiceLineItemId().equals(invoiceA.id())));
        assertCompleteCover(plan);

        var input = matcher.lastInput().orElseThrow();
        assertEquals("REF", input.reference().label());
        assertEquals(List.of("C1", "C2"), input.invoiceCandidates().stream()
                .map(line -> line.label()).toList());
        assertEquals("REF-SKU", input.reference().itemCode());
        assertEquals("reference description", input.reference().description());
        assertEquals("unit", input.reference().unit());
        assertFalse(input.toString().contains(reference.id().toString()));
    }

    @Test
    void unavailableAiFallsBackToBestDeterministicAmbiguousPair() {
        MatchableLineItem reference = line(10, 0, null, "reference item");
        MatchableLineItem best = line(11, 0, null, "best invoice item");
        MatchableLineItem alternative = line(12, 1, null, "alternative invoice item");
        LineItemMatchingResult result = result(
                List.of(reference), List.of(best, alternative),
                List.of(group(reference, candidate(best, 0.83456), candidate(alternative, 0.72))));
        FakeAmbiguousLineMatcher matcher = new FakeAmbiguousLineMatcher();

        MatchingPlan plan = assistant(matcher, 5).assist(result);

        LineItemMatch fallback = matchForReference(plan, reference.id());
        assertEquals(LineMatchStatus.NEEDS_REVIEW, fallback.status());
        assertEquals(LineMatchMethod.FUZZY, fallback.method());
        assertEquals(best.id(), fallback.invoiceLineItemId());
        assertEquals(new BigDecimal("0.8346"), fallback.confidence());
        assertEquals(1, matcher.callCount());
        assertCompleteCover(plan);
    }

    @Test
    void invalidAiCandidateFallsBackInsteadOfFailingOrUsingAnUnlistedLine() {
        MatchableLineItem reference = line(20, 0, null, "reference item");
        MatchableLineItem best = line(21, 0, null, "best invoice item");
        MatchableLineItem alternative = line(22, 1, null, "alternative invoice item");
        LineItemMatchingResult result = result(
                List.of(reference), List.of(best, alternative),
                List.of(group(reference, candidate(best, 0.88), candidate(alternative, 0.74))));
        FakeAmbiguousLineMatcher matcher = new FakeAmbiguousLineMatcher();
        matcher.configureInvalidResponse();

        MatchingPlan plan = assistant(matcher, 5).assist(result);

        LineItemMatch fallback = matchForReference(plan, reference.id());
        assertEquals(LineMatchMethod.FUZZY, fallback.method());
        assertEquals(best.id(), fallback.invoiceLineItemId());
        assertCompleteCover(plan);
    }

    @Test
    void providerExceptionDoesNotFailMatchingAndUsesDeterministicFallback() {
        MatchableLineItem reference = line(23, 0, null, "reference item");
        MatchableLineItem best = line(24, 0, null, "best invoice item");
        MatchableLineItem alternative = line(25, 1, null, "alternative invoice item");
        LineItemMatchingResult result = result(
                List.of(reference), List.of(best, alternative),
                List.of(group(reference, candidate(best, 0.84), candidate(alternative, 0.74))));
        AmbiguousLineMatcher unavailable = input -> {
            throw new IllegalStateException("synthetic provider failure");
        };

        MatchingPlan plan = new AmbiguousLineMatchingAssistant(unavailable, 5, ids()).assist(result);

        LineItemMatch fallback = matchForReference(plan, reference.id());
        assertEquals(LineMatchStatus.NEEDS_REVIEW, fallback.status());
        assertEquals(LineMatchMethod.FUZZY, fallback.method());
        assertEquals(best.id(), fallback.invoiceLineItemId());
        assertCompleteCover(plan);
    }

    @Test
    void reservesCandidatesInReferenceOrderAndMakesUnavailableLinesUnmatched() {
        MatchableLineItem firstReference = line(30, 0, null, "first reference");
        MatchableLineItem secondReference = line(31, 1, null, "second reference");
        MatchableLineItem firstInvoice = line(32, 0, null, "first invoice");
        MatchableLineItem secondInvoice = line(33, 1, null, "second invoice");
        LineItemMatchingResult result = result(
                List.of(firstReference, secondReference), List.of(firstInvoice, secondInvoice),
                List.of(
                        group(firstReference, candidate(firstInvoice, 0.9), candidate(secondInvoice, 0.8)),
                        group(secondReference, candidate(secondInvoice, 0.85))));
        FakeAmbiguousLineMatcher matcher = new FakeAmbiguousLineMatcher();
        matcher.configureCandidate("C2");

        MatchingPlan plan = assistant(matcher, 5).assist(result);

        LineItemMatch first = matchForReference(plan, firstReference.id());
        assertEquals(LineMatchMethod.AI, first.method());
        assertEquals(secondInvoice.id(), first.invoiceLineItemId());
        LineItemMatch second = matchForReference(plan, secondReference.id());
        assertEquals(LineMatchStatus.UNMATCHED_REFERENCE, second.status());
        assertEquals(LineMatchMethod.NONE, second.method());
        assertNull(second.confidence());
        assertTrue(plan.matches().stream().anyMatch(match ->
                match.status() == LineMatchStatus.UNMATCHED_INVOICE
                        && match.invoiceLineItemId().equals(firstInvoice.id())));
        assertEquals(1, matcher.callCount(), "a group with no available candidates must not call the matcher");
        assertCompleteCover(plan);
    }

    @Test
    void automaticMatchIsReservedBeforeAmbiguousCandidatesReachTheMatcher() {
        MatchableLineItem automaticReference = line(34, 0, "EXACT-1", "automatic reference");
        MatchableLineItem ambiguousReference = line(35, 1, null, "ambiguous reference");
        MatchableLineItem automaticInvoice = line(36, 0, "EXACT-1", "automatic invoice");
        MatchableLineItem ambiguousInvoice = line(37, 1, null, "ambiguous invoice");
        LineItemMatch automatic = new LineItemMatch(
                uuid(134), ANALYSIS_ID, automaticReference.id(), automaticInvoice.id(),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, new BigDecimal("1.0000"), 0, null, NOW, NOW);
        LineItemMatch placeholder = new LineItemMatch(
                uuid(135), ANALYSIS_ID, ambiguousReference.id(), ambiguousInvoice.id(),
                LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.FUZZY, new BigDecimal("0.8000"), 0, null, NOW, NOW);
        AmbiguousMatchCandidates group = group(
                ambiguousReference,
                candidate(automaticInvoice, 0.95),
                candidate(ambiguousInvoice, 0.80));
        LineItemMatchingResult result = new LineItemMatchingResult(
                new MatchingPlan(ANALYSIS_ID,
                        List.of(automaticReference, ambiguousReference),
                        List.of(automaticInvoice, ambiguousInvoice),
                        List.of(automatic, placeholder)),
                List.of(group));
        FakeAmbiguousLineMatcher matcher = new FakeAmbiguousLineMatcher();
        matcher.configureCandidate("C1");

        MatchingPlan plan = assistant(matcher, 5).assist(result);

        assertEquals(LineMatchStatus.MATCHED, matchForReference(plan, automaticReference.id()).status());
        LineItemMatch suggestion = matchForReference(plan, ambiguousReference.id());
        assertEquals(LineMatchStatus.NEEDS_REVIEW, suggestion.status());
        assertEquals(LineMatchMethod.AI, suggestion.method());
        assertEquals(ambiguousInvoice.id(), suggestion.invoiceLineItemId());
        assertEquals(1, matcher.callCount());
        assertEquals(List.of("ambiguous invoice"), matcher.lastInput().orElseThrow()
                .invoiceCandidates().stream().map(line -> line.description()).toList());
        assertCompleteCover(plan);
    }

    @Test
    void noMatchSuggestionLeavesReferenceAndInvoiceForHumanReview() {
        MatchableLineItem reference = line(40, 0, null, "reference item");
        MatchableLineItem invoice = line(41, 0, null, "invoice item");
        LineItemMatchingResult result = result(
                List.of(reference), List.of(invoice),
                List.of(group(reference, candidate(invoice, 0.71))));
        FakeAmbiguousLineMatcher matcher = new FakeAmbiguousLineMatcher();
        matcher.configureNoMatch();

        MatchingPlan plan = assistant(matcher, 5).assist(result);

        assertEquals(LineMatchStatus.UNMATCHED_REFERENCE, matchForReference(plan, reference.id()).status());
        assertTrue(plan.matches().stream().anyMatch(match ->
                match.status() == LineMatchStatus.UNMATCHED_INVOICE
                        && match.invoiceLineItemId().equals(invoice.id())));
        assertCompleteCover(plan);
    }

    @Test
    void candidateLimitIsEnforcedByTheApplicationOrchestrator() {
        FakeAmbiguousLineMatcher matcher = new FakeAmbiguousLineMatcher();
        MatchableLineItem reference = line(50, 0, null, "reference item");
        MatchableLineItem invoiceA = line(51, 0, null, "candidate one");
        MatchableLineItem invoiceB = line(52, 1, null, "candidate two");
        MatchableLineItem invoiceC = line(53, 2, null, "candidate three");
        LineItemMatchingResult result = result(
                List.of(reference), List.of(invoiceA, invoiceB, invoiceC),
                List.of(group(reference, candidate(invoiceA, 0.9), candidate(invoiceB, 0.8), candidate(invoiceC, 0.7))));

        assistant(matcher, 2).assist(result);

        assertEquals(2, matcher.lastInput().orElseThrow().invoiceCandidates().size());
        assertEquals(List.of("candidate one", "candidate two"), matcher.lastInput().orElseThrow()
                .invoiceCandidates().stream().map(line -> line.description()).toList());
    }

    private static AmbiguousLineMatchingAssistant assistant(FakeAmbiguousLineMatcher matcher, int maxCandidates) {
        return new AmbiguousLineMatchingAssistant(matcher, maxCandidates, ids());
    }

    private static LineItemMatchingResult result(
            List<MatchableLineItem> references,
            List<MatchableLineItem> invoices,
            List<AmbiguousMatchCandidates> groups) {
        List<LineItemMatch> rows = new java.util.ArrayList<>(groups.stream()
                .map(group -> {
                    AmbiguousMatchCandidates.Candidate fallback = group.candidates().getFirst();
                    return new LineItemMatch(
                            uuid(100 + group.referenceLine().position()), ANALYSIS_ID,
                            group.referenceLine().id(), fallback.lineItem().id(),
                            LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.FUZZY,
                            BigDecimal.valueOf(fallback.score()).setScale(4, RoundingMode.HALF_UP),
                            0, null, NOW, NOW);
                })
                .toList());
        java.util.Set<UUID> pairedInvoices = rows.stream()
                .map(LineItemMatch::invoiceLineItemId)
                .collect(java.util.stream.Collectors.toSet());
        int nextId = 200;
        for (MatchableLineItem invoice : invoices) {
            if (!pairedInvoices.contains(invoice.id())) {
                rows.add(new LineItemMatch(
                        uuid(nextId++), ANALYSIS_ID, null, invoice.id(),
                        LineMatchStatus.UNMATCHED_INVOICE, LineMatchMethod.NONE, null, 0, null, NOW, NOW));
            }
        }
        return new LineItemMatchingResult(new MatchingPlan(ANALYSIS_ID, references, invoices, rows), groups);
    }

    private static AmbiguousMatchCandidates group(
            MatchableLineItem reference,
            AmbiguousMatchCandidates.Candidate... candidates) {
        return new AmbiguousMatchCandidates(reference, List.of(candidates));
    }

    private static AmbiguousMatchCandidates.Candidate candidate(MatchableLineItem line, double score) {
        return new AmbiguousMatchCandidates.Candidate(line, score);
    }

    private static MatchableLineItem line(int id, int position, String code, String description) {
        return new MatchableLineItem(uuid(id), position, code, description, description.toLowerCase(), "unit");
    }

    private static LineItemMatch matchForReference(MatchingPlan plan, UUID referenceId) {
        return plan.matches().stream()
                .filter(match -> referenceId.equals(match.referenceLineItemId()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertCompleteCover(MatchingPlan plan) {
        assertEquals(plan.referenceLines().size(), plan.matches().stream()
                .filter(match -> match.referenceLineItemId() != null).count());
        assertEquals(plan.invoiceLines().size(), plan.matches().stream()
                .filter(match -> match.invoiceLineItemId() != null).count());
        assertEquals(plan.matches().size(), plan.matches().stream().map(LineItemMatch::id).distinct().count());
    }

    private static Supplier<UUID> ids() {
        AtomicInteger next = new AtomicInteger(900);
        return () -> uuid(next.getAndIncrement());
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
