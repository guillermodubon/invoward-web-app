package io.github.guillermodubon.invoward.reconciliation.application.model;

import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Immutable match plan that proves every confirmed line is represented exactly once. */
public record MatchingPlan(
        UUID analysisId,
        List<MatchableLineItem> referenceLines,
        List<MatchableLineItem> invoiceLines,
        List<LineItemMatch> matches) {

    public MatchingPlan {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        referenceLines = List.copyOf(referenceLines);
        invoiceLines = List.copyOf(invoiceLines);
        matches = List.copyOf(matches);

        Set<UUID> expectedReferenceIds = uniqueIds(referenceLines, "reference line");
        Set<UUID> expectedInvoiceIds = uniqueIds(invoiceLines, "invoice line");
        Set<UUID> coveredReferenceIds = new HashSet<>();
        Set<UUID> coveredInvoiceIds = new HashSet<>();

        for (LineItemMatch match : matches) {
            Objects.requireNonNull(match, "matches must not contain null");
            if (!analysisId.equals(match.analysisId())) {
                throw new IllegalArgumentException("all matches must belong to the plan analysis");
            }
            switch (match.status()) {
                case MATCHED, NEEDS_REVIEW -> {
                    cover(match.referenceLineItemId(), expectedReferenceIds, coveredReferenceIds, "reference");
                    cover(match.invoiceLineItemId(), expectedInvoiceIds, coveredInvoiceIds, "invoice");
                }
                case UNMATCHED_REFERENCE ->
                        cover(match.referenceLineItemId(), expectedReferenceIds, coveredReferenceIds, "reference");
                case UNMATCHED_INVOICE ->
                        cover(match.invoiceLineItemId(), expectedInvoiceIds, coveredInvoiceIds, "invoice");
            }
        }
        if (!coveredReferenceIds.equals(expectedReferenceIds) || !coveredInvoiceIds.equals(expectedInvoiceIds)) {
            throw new IllegalArgumentException("matches must cover every confirmed reference and invoice line exactly once");
        }
    }

    private static Set<UUID> uniqueIds(List<MatchableLineItem> lines, String side) {
        Set<UUID> ids = lines.stream()
                .map(line -> Objects.requireNonNull(line, side + " lines must not contain null").id())
                .collect(Collectors.toSet());
        if (ids.size() != lines.size()) {
            throw new IllegalArgumentException(side + " line identities must be unique");
        }
        return ids;
    }

    private static void cover(UUID lineId, Set<UUID> expected, Set<UUID> covered, String side) {
        if (!expected.contains(lineId)) {
            throw new IllegalArgumentException("match contains a line outside confirmed " + side + " extraction");
        }
        if (!covered.add(lineId)) {
            throw new IllegalArgumentException("confirmed " + side + " line must be covered exactly once");
        }
    }
}
