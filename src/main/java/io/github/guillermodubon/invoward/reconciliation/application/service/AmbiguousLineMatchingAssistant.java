package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousLineMatchingInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchCandidates;
import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchSuggestion;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchingResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchingPlan;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Applies optional AI suggestions to deterministic ambiguous pairs while preserving one-to-one coverage. */
public final class AmbiguousLineMatchingAssistant {

    private static final int MIN_AI_CANDIDATES = 2;
    private static final int MAX_AI_CANDIDATES = 10;

    private static final Comparator<AmbiguousMatchCandidates.Candidate> CANDIDATE_ORDER =
            Comparator.comparingDouble(AmbiguousMatchCandidates.Candidate::score)
                    .reversed()
                    .thenComparingInt(candidate -> candidate.lineItem().position())
                    .thenComparing(candidate -> candidate.lineItem().id());

    private static final Comparator<AmbiguousMatchCandidates> REFERENCE_ORDER =
            Comparator.comparingInt((AmbiguousMatchCandidates group) -> group.referenceLine().position())
                    .thenComparing(group -> group.referenceLine().id());

    private final AmbiguousLineMatcher matcher;
    private final int maxCandidates;
    private final Supplier<UUID> matchIdGenerator;

    public AmbiguousLineMatchingAssistant(
            AmbiguousLineMatcher matcher,
            int maxCandidates,
            Supplier<UUID> matchIdGenerator) {
        this.matcher = Objects.requireNonNull(matcher, "matcher must not be null");
        if (maxCandidates < MIN_AI_CANDIDATES || maxCandidates > MAX_AI_CANDIDATES) {
            throw new IllegalArgumentException("AI candidate limit must be between 2 and 10");
        }
        this.maxCandidates = maxCandidates;
        this.matchIdGenerator = Objects.requireNonNull(matchIdGenerator, "matchIdGenerator must not be null");
    }

    /**
     * Returns the deterministic plan with ambiguous candidates optionally suggested by AI.
     * AI-selected pairs remain NEEDS_REVIEW and all provider keys are resolved only against this request's labels.
     */
    public MatchingPlan assist(LineItemMatchingResult result) {
        Objects.requireNonNull(result, "result must not be null");
        MatchingPlan originalPlan = result.plan();

        Map<UUID, MatchableLineItem> references = indexLines(originalPlan.referenceLines());
        Map<UUID, MatchableLineItem> invoices = indexLines(originalPlan.invoiceLines());
        Map<UUID, LineItemMatch> ambiguousRows = indexAmbiguousRows(originalPlan.matches());
        Map<UUID, LineItemMatch> unmatchedInvoiceRows = indexUnmatchedInvoiceRows(originalPlan.matches());
        validateAmbiguousGroups(result, references, invoices, ambiguousRows);

        List<LineItemMatch> resolvedMatches = new ArrayList<>();
        Set<UUID> usedMatchIds = new HashSet<>();
        Set<UUID> reservedInvoiceIds = new HashSet<>();

        for (LineItemMatch match : originalPlan.matches()) {
            if (isAmbiguousPlaceholder(match) || match.status() == LineMatchStatus.UNMATCHED_INVOICE) {
                continue;
            }
            addMatch(resolvedMatches, usedMatchIds, match);
            if (match.status() == LineMatchStatus.MATCHED) {
                reservedInvoiceIds.add(match.invoiceLineItemId());
            }
        }

        List<AmbiguousMatchCandidates> groups = result.ambiguousCandidates().stream()
                .sorted(REFERENCE_ORDER)
                .toList();
        for (AmbiguousMatchCandidates group : groups) {
            LineItemMatch placeholder = ambiguousRows.get(group.referenceLine().id());
            List<AmbiguousMatchCandidates.Candidate> available = group.candidates().stream()
                    .filter(candidate -> !reservedInvoiceIds.contains(candidate.lineItem().id()))
                    .sorted(CANDIDATE_ORDER)
                    .toList();

            if (available.isEmpty()) {
                addMatch(resolvedMatches, usedMatchIds,
                        unmatchedReference(placeholder, group.referenceLine().id()));
                continue;
            }

            List<AmbiguousMatchCandidates.Candidate> shortlist = available.stream()
                    .limit(maxCandidates)
                    .toList();
            Map<String, AmbiguousMatchCandidates.Candidate> candidatesByLabel = new LinkedHashMap<>();
            AmbiguousLineMatchingInput input = createInput(group.referenceLine(), shortlist, candidatesByLabel);
            Optional<AmbiguousMatchSuggestion> suggestion = suggestSafely(input);

            if (suggestion.isPresent() && suggestion.get().isNoMatch()) {
                addMatch(resolvedMatches, usedMatchIds,
                        unmatchedReference(placeholder, group.referenceLine().id()));
                continue;
            }

            AmbiguousMatchCandidates.Candidate selected = suggestion
                    .map(AmbiguousMatchSuggestion::candidateKey)
                    .map(candidatesByLabel::get)
                    .orElse(null);
            LineMatchMethod method;
            if (selected == null || reservedInvoiceIds.contains(selected.lineItem().id())) {
                selected = shortlist.getFirst();
                method = LineMatchMethod.FUZZY;
            } else {
                method = LineMatchMethod.AI;
            }

            addMatch(resolvedMatches, usedMatchIds,
                    needsReview(placeholder, group.referenceLine().id(), selected, method));
            reservedInvoiceIds.add(selected.lineItem().id());
        }

        Set<UUID> coveredInvoiceIds = new HashSet<>();
        resolvedMatches.stream()
                .map(LineItemMatch::invoiceLineItemId)
                .filter(Objects::nonNull)
                .forEach(coveredInvoiceIds::add);

        for (MatchableLineItem invoice : originalPlan.invoiceLines()) {
            if (coveredInvoiceIds.contains(invoice.id())) {
                continue;
            }
            LineItemMatch originalUnmatched = unmatchedInvoiceRows.get(invoice.id());
            if (originalUnmatched != null) {
                addMatch(resolvedMatches, usedMatchIds, originalUnmatched);
            } else {
                addMatch(resolvedMatches, usedMatchIds,
                        unmatchedInvoice(originalPlan.analysisId(), invoice.id(), nextMatchId(usedMatchIds),
                                matchingTimestamp(originalPlan)));
            }
        }

        resolvedMatches.sort(matchOrder(references, invoices));
        return new MatchingPlan(
                originalPlan.analysisId(),
                originalPlan.referenceLines(),
                originalPlan.invoiceLines(),
                resolvedMatches);
    }

    private static void validateAmbiguousGroups(
            LineItemMatchingResult result,
            Map<UUID, MatchableLineItem> references,
            Map<UUID, MatchableLineItem> invoices,
            Map<UUID, LineItemMatch> ambiguousRows) {
        if (ambiguousRows.size() != result.ambiguousCandidates().size()) {
            throw new IllegalArgumentException("each ambiguous candidate group must have exactly one fuzzy review row");
        }
        for (AmbiguousMatchCandidates group : result.ambiguousCandidates()) {
            MatchableLineItem reference = references.get(group.referenceLine().id());
            if (!group.referenceLine().equals(reference)) {
                throw new IllegalArgumentException("ambiguous reference must belong to the matching plan");
            }
            LineItemMatch row = ambiguousRows.get(group.referenceLine().id());
            if (row == null) {
                throw new IllegalArgumentException("ambiguous reference must have one deterministic fallback row");
            }
            boolean containsFallback = false;
            for (AmbiguousMatchCandidates.Candidate candidate : group.candidates()) {
                if (!candidate.lineItem().equals(invoices.get(candidate.lineItem().id()))) {
                    throw new IllegalArgumentException("ambiguous invoice candidate must belong to the matching plan");
                }
                containsFallback |= candidate.lineItem().id().equals(row.invoiceLineItemId());
            }
            if (!containsFallback) {
                throw new IllegalArgumentException("ambiguous candidates must contain their deterministic fallback pair");
            }
        }
    }

    private static Map<UUID, MatchableLineItem> indexLines(List<MatchableLineItem> lines) {
        Map<UUID, MatchableLineItem> indexed = new HashMap<>();
        for (MatchableLineItem line : lines) {
            indexed.put(line.id(), line);
        }
        return indexed;
    }

    private static Map<UUID, LineItemMatch> indexAmbiguousRows(List<LineItemMatch> matches) {
        Map<UUID, LineItemMatch> indexed = new HashMap<>();
        for (LineItemMatch match : matches) {
            if (!isAmbiguousPlaceholder(match)) {
                continue;
            }
            if (indexed.put(match.referenceLineItemId(), match) != null) {
                throw new IllegalArgumentException("ambiguous reference must have one deterministic fallback row");
            }
        }
        return indexed;
    }

    private static Map<UUID, LineItemMatch> indexUnmatchedInvoiceRows(List<LineItemMatch> matches) {
        Map<UUID, LineItemMatch> indexed = new HashMap<>();
        for (LineItemMatch match : matches) {
            if (match.status() == LineMatchStatus.UNMATCHED_INVOICE) {
                indexed.put(match.invoiceLineItemId(), match);
            }
        }
        return indexed;
    }

    private static boolean isAmbiguousPlaceholder(LineItemMatch match) {
        return match.status() == LineMatchStatus.NEEDS_REVIEW && match.method() == LineMatchMethod.FUZZY;
    }

    private static AmbiguousLineMatchingInput createInput(
            MatchableLineItem reference,
            List<AmbiguousMatchCandidates.Candidate> candidates,
            Map<String, AmbiguousMatchCandidates.Candidate> candidatesByLabel) {
        List<AmbiguousLineMatchingInput.Line> invoiceLines = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            AmbiguousMatchCandidates.Candidate candidate = candidates.get(index);
            String label = "C" + (index + 1);
            candidatesByLabel.put(label, candidate);
            invoiceLines.add(providerLine(label, candidate.lineItem()));
        }
        return new AmbiguousLineMatchingInput(providerLine("REF", reference), invoiceLines);
    }

    private static AmbiguousLineMatchingInput.Line providerLine(String label, MatchableLineItem line) {
        return new AmbiguousLineMatchingInput.Line(label, line.itemCode(), line.description(), line.unit());
    }

    private Optional<AmbiguousMatchSuggestion> suggestSafely(AmbiguousLineMatchingInput input) {
        try {
            Optional<AmbiguousMatchSuggestion> suggestion = matcher.suggest(input);
            return suggestion == null ? Optional.empty() : suggestion;
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static LineItemMatch needsReview(
            LineItemMatch template,
            UUID referenceId,
            AmbiguousMatchCandidates.Candidate candidate,
            LineMatchMethod method) {
        return new LineItemMatch(
                template.id(), template.analysisId(), referenceId, candidate.lineItem().id(),
                LineMatchStatus.NEEDS_REVIEW, method, BigDecimal.valueOf(candidate.score()).setScale(4, RoundingMode.HALF_UP),
                template.version(), template.reviewedAt(), template.createdAt(), template.updatedAt());
    }

    private static LineItemMatch unmatchedReference(LineItemMatch template, UUID referenceId) {
        return new LineItemMatch(
                template.id(), template.analysisId(), referenceId, null,
                LineMatchStatus.UNMATCHED_REFERENCE, LineMatchMethod.NONE, null,
                template.version(), null, template.createdAt(), template.updatedAt());
    }

    private static LineItemMatch unmatchedInvoice(
            UUID analysisId,
            UUID invoiceId,
            UUID matchId,
            Instant timestamp) {
        return new LineItemMatch(
                matchId, analysisId, null, invoiceId,
                LineMatchStatus.UNMATCHED_INVOICE, LineMatchMethod.NONE, null,
                0, null, timestamp, timestamp);
    }

    private UUID nextMatchId(Set<UUID> usedMatchIds) {
        UUID id = Objects.requireNonNull(matchIdGenerator.get(), "generated match id must not be null");
        if (usedMatchIds.contains(id)) {
            throw new IllegalArgumentException("generated match identities must be unique");
        }
        return id;
    }

    private static void addMatch(List<LineItemMatch> matches, Set<UUID> usedIds, LineItemMatch match) {
        if (!usedIds.add(match.id())) {
            throw new IllegalArgumentException("match identities must be unique");
        }
        matches.add(match);
    }

    private static Instant matchingTimestamp(MatchingPlan plan) {
        return plan.matches().stream()
                .map(LineItemMatch::createdAt)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("cannot create unmatched rows without a plan timestamp"));
    }

    private static Comparator<LineItemMatch> matchOrder(
            Map<UUID, MatchableLineItem> references,
            Map<UUID, MatchableLineItem> invoices) {
        return Comparator.comparing((LineItemMatch match) -> match.referenceLineItemId() == null)
                .thenComparingInt(match -> match.referenceLineItemId() == null
                        ? invoices.get(match.invoiceLineItemId()).position()
                        : references.get(match.referenceLineItemId()).position())
                .thenComparing(LineItemMatch::id);
    }
}
