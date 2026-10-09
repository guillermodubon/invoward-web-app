package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchCandidates;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchingResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchingPlan;
import io.github.guillermodubon.invoward.reconciliation.domain.LineDescriptionCandidateIndex;
import io.github.guillermodubon.invoward.reconciliation.domain.LineDescriptionSimilarity;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemCodeNormalizer;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Stateless deterministic matching engine. It performs no persistence or provider calls. */
public final class LineItemMatchingEngine {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal MAX_MARGIN = new BigDecimal("0.50");
    private static final BigDecimal PERFECT_CONFIDENCE = new BigDecimal("1.0000");
    private static final Comparator<MatchableLineItem> LINE_ORDER = Comparator
            .comparingInt(MatchableLineItem::position)
            .thenComparing(MatchableLineItem::id);

    private final LineItemCodeNormalizer codeNormalizer;
    private final LineDescriptionSimilarity descriptionSimilarity;
    private final BigDecimal autoThreshold;
    private final BigDecimal ambiguousThreshold;
    private final BigDecimal minMargin;
    private final int maxCandidates;

    public LineItemMatchingEngine(
            LineItemCodeNormalizer codeNormalizer,
            LineDescriptionSimilarity descriptionSimilarity,
            BigDecimal autoThreshold,
            BigDecimal ambiguousThreshold,
            BigDecimal minMargin,
            int maxCandidates) {
        this.codeNormalizer = Objects.requireNonNull(codeNormalizer, "codeNormalizer must not be null");
        this.descriptionSimilarity = Objects.requireNonNull(
                descriptionSimilarity, "descriptionSimilarity must not be null");
        this.autoThreshold = Objects.requireNonNull(autoThreshold, "autoThreshold must not be null");
        this.ambiguousThreshold = Objects.requireNonNull(
                ambiguousThreshold, "ambiguousThreshold must not be null");
        this.minMargin = Objects.requireNonNull(minMargin, "minMargin must not be null");
        if (ambiguousThreshold.signum() <= 0
                || ambiguousThreshold.compareTo(autoThreshold) >= 0
                || autoThreshold.compareTo(ONE) > 0) {
            throw new IllegalArgumentException(
                    "thresholds must satisfy 0 < ambiguous threshold < auto threshold <= 1");
        }
        if (minMargin.signum() < 0 || minMargin.compareTo(MAX_MARGIN) > 0) {
            throw new IllegalArgumentException("minMargin must be between 0 and 0.50");
        }
        if (maxCandidates < 1 || maxCandidates > 50) {
            throw new IllegalArgumentException("maxCandidates must be between 1 and 50");
        }
        this.maxCandidates = maxCandidates;
    }

    public LineItemMatchingResult match(
            UUID analysisId,
            List<MatchableLineItem> referenceInput,
            List<MatchableLineItem> invoiceInput,
            Instant createdAt,
            Supplier<UUID> matchIdGenerator) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(matchIdGenerator, "matchIdGenerator must not be null");

        List<MatchableLineItem> referenceLines = orderedUniqueLines(referenceInput, "reference");
        List<MatchableLineItem> invoiceLines = orderedUniqueLines(invoiceInput, "invoice");
        List<MatchableLineItem> remainingReferences = new ArrayList<>(referenceLines);
        List<MatchableLineItem> remainingInvoices = new ArrayList<>(invoiceLines);
        List<LineItemMatch> matches = new ArrayList<>();
        List<AmbiguousMatchCandidates> ambiguous = new ArrayList<>();
        Set<UUID> generatedMatchIds = new HashSet<>();

        matchUniquePhase(analysisId, remainingReferences, remainingInvoices,
                line -> codeNormalizer.normalize(line.itemCode()), LineMatchMethod.SKU,
                createdAt, matchIdGenerator, generatedMatchIds, matches);
        matchUniquePhase(analysisId, remainingReferences, remainingInvoices,
                line -> line.normalizedDescription().isBlank() ? null : line.normalizedDescription(),
                LineMatchMethod.NORMALIZED_NAME, createdAt, matchIdGenerator, generatedMatchIds, matches);

        matchFuzzyAutoPhase(analysisId, remainingReferences, remainingInvoices,
                createdAt, matchIdGenerator, generatedMatchIds, matches);
        completeAmbiguousPhase(analysisId, remainingReferences, remainingInvoices,
                createdAt, matchIdGenerator, generatedMatchIds, matches, ambiguous);
        addUnmatchedRows(analysisId, remainingReferences, true, createdAt,
                matchIdGenerator, generatedMatchIds, matches);
        addUnmatchedRows(analysisId, remainingInvoices, false, createdAt,
                matchIdGenerator, generatedMatchIds, matches);

        return new LineItemMatchingResult(
                new MatchingPlan(analysisId, referenceLines, invoiceLines, matches), ambiguous);
    }

    private void matchUniquePhase(
            UUID analysisId,
            List<MatchableLineItem> references,
            List<MatchableLineItem> invoices,
            Function<MatchableLineItem, String> keyExtractor,
            LineMatchMethod method,
            Instant createdAt,
            Supplier<UUID> matchIdGenerator,
            Set<UUID> generatedMatchIds,
            List<LineItemMatch> matches) {
        Map<String, List<MatchableLineItem>> referenceGroups = groupByKey(references, keyExtractor);
        Map<String, List<MatchableLineItem>> invoiceGroups = groupByKey(invoices, keyExtractor);
        Set<UUID> matchedReferences = new HashSet<>();
        Set<UUID> matchedInvoices = new HashSet<>();

        for (MatchableLineItem reference : references) {
            String key = keyExtractor.apply(reference);
            if (key == null || key.isBlank()) {
                continue;
            }
            List<MatchableLineItem> referenceGroup = referenceGroups.get(key);
            List<MatchableLineItem> invoiceGroup = invoiceGroups.get(key);
            if (referenceGroup.size() != 1 || invoiceGroup == null || invoiceGroup.size() != 1) {
                continue;
            }
            MatchableLineItem invoice = invoiceGroup.getFirst();
            matches.add(createMatch(analysisId, reference.id(), invoice.id(), LineMatchStatus.MATCHED,
                    method, PERFECT_CONFIDENCE, createdAt, matchIdGenerator, generatedMatchIds));
            matchedReferences.add(reference.id());
            matchedInvoices.add(invoice.id());
        }
        references.removeIf(line -> matchedReferences.contains(line.id()));
        invoices.removeIf(line -> matchedInvoices.contains(line.id()));
    }

    private void matchFuzzyAutoPhase(
            UUID analysisId,
            List<MatchableLineItem> references,
            List<MatchableLineItem> invoices,
            Instant createdAt,
            Supplier<UUID> matchIdGenerator,
            Set<UUID> generatedMatchIds,
            List<LineItemMatch> matches) {
        while (!references.isEmpty() && !invoices.isEmpty()) {
            RankedPairs rankedPairs = rank(references, invoices);
            List<ScoredPair> automaticPairs = new ArrayList<>();
            for (MatchableLineItem reference : references) {
                List<ScoredPair> candidates = rankedPairs.byReference().getOrDefault(reference.id(), List.of());
                if (candidates.isEmpty()) {
                    continue;
                }
                ScoredPair best = candidates.getFirst();
                List<ScoredPair> invoiceCandidates = rankedPairs.byInvoice().getOrDefault(best.invoice().id(), List.of());
                if (invoiceCandidates.isEmpty() || !invoiceCandidates.getFirst().reference().id().equals(reference.id())) {
                    continue;
                }
                if (meetsAutoCriteria(best, candidates, invoiceCandidates)) {
                    automaticPairs.add(best);
                }
            }
            if (automaticPairs.isEmpty()) {
                return;
            }

            Set<UUID> matchedReferences = new HashSet<>();
            Set<UUID> matchedInvoices = new HashSet<>();
            for (ScoredPair pair : automaticPairs) {
                if (!matchedReferences.add(pair.reference().id()) || !matchedInvoices.add(pair.invoice().id())) {
                    throw new IllegalStateException("mutual-best fuzzy pairs must be one-to-one");
                }
                matches.add(createMatch(analysisId, pair.reference().id(), pair.invoice().id(),
                        LineMatchStatus.MATCHED, LineMatchMethod.FUZZY, confidence(pair.score()),
                        createdAt, matchIdGenerator, generatedMatchIds));
            }
            references.removeIf(line -> matchedReferences.contains(line.id()));
            invoices.removeIf(line -> matchedInvoices.contains(line.id()));
        }
    }

    private boolean meetsAutoCriteria(
            ScoredPair best, List<ScoredPair> referenceCandidates, List<ScoredPair> invoiceCandidates) {
        return BigDecimal.valueOf(best.score()).compareTo(autoThreshold) >= 0
                && margin(referenceCandidates).compareTo(minMargin) >= 0
                && margin(invoiceCandidates).compareTo(minMargin) >= 0;
    }

    private void completeAmbiguousPhase(
            UUID analysisId,
            List<MatchableLineItem> references,
            List<MatchableLineItem> invoices,
            Instant createdAt,
            Supplier<UUID> matchIdGenerator,
            Set<UUID> generatedMatchIds,
            List<LineItemMatch> matches,
            List<AmbiguousMatchCandidates> ambiguous) {
        if (references.isEmpty() || invoices.isEmpty()) {
            return;
        }
        RankedPairs rankedPairs = rank(references, invoices);
        Set<UUID> matchedReferences = new HashSet<>();
        Set<UUID> matchedInvoices = new HashSet<>();

        for (MatchableLineItem reference : references) {
            List<ScoredPair> candidates = rankedPairs.byReference().getOrDefault(reference.id(), List.of());
            if (candidates.isEmpty()) {
                continue;
            }
            ScoredPair best = candidates.getFirst();
            List<ScoredPair> invoiceCandidates = rankedPairs.byInvoice().getOrDefault(best.invoice().id(), List.of());
            if (invoiceCandidates.isEmpty()
                    || !invoiceCandidates.getFirst().reference().id().equals(reference.id())
                    || BigDecimal.valueOf(best.score()).compareTo(ambiguousThreshold) < 0) {
                continue;
            }
            if (!matchedReferences.add(reference.id()) || !matchedInvoices.add(best.invoice().id())) {
                throw new IllegalStateException("mutual-best ambiguous pairs must be one-to-one");
            }

            matches.add(createMatch(analysisId, reference.id(), best.invoice().id(), LineMatchStatus.NEEDS_REVIEW,
                    LineMatchMethod.FUZZY, confidence(best.score()), createdAt, matchIdGenerator, generatedMatchIds));
            List<AmbiguousMatchCandidates.Candidate> eligible = candidates.stream()
                    .filter(candidate -> BigDecimal.valueOf(candidate.score()).compareTo(ambiguousThreshold) >= 0)
                    .map(candidate -> new AmbiguousMatchCandidates.Candidate(candidate.invoice(), candidate.score()))
                    .toList();
            ambiguous.add(new AmbiguousMatchCandidates(reference, eligible));
        }
        references.removeIf(line -> matchedReferences.contains(line.id()));
        invoices.removeIf(line -> matchedInvoices.contains(line.id()));
    }

    private RankedPairs rank(List<MatchableLineItem> references, List<MatchableLineItem> invoices) {
        List<LineDescriptionCandidateIndex.Candidate> candidates = invoices.stream()
                .map(line -> new LineDescriptionCandidateIndex.Candidate(
                        line.id(), line.position(), line.normalizedDescription()))
                .toList();
        LineDescriptionCandidateIndex index = new LineDescriptionCandidateIndex(
                candidates, descriptionSimilarity, maxCandidates);
        Map<UUID, MatchableLineItem> invoicesById = invoices.stream()
                .collect(Collectors.toMap(MatchableLineItem::id, Function.identity()));
        Map<UUID, List<ScoredPair>> byReference = new LinkedHashMap<>();
        Map<UUID, List<ScoredPair>> byInvoice = new HashMap<>();

        for (MatchableLineItem reference : references) {
            List<ScoredPair> rankedForReference = index.rank(reference.normalizedDescription()).stream()
                    .map(candidate -> new ScoredPair(reference, invoicesById.get(candidate.candidate().id()), candidate.score()))
                    .toList();
            byReference.put(reference.id(), rankedForReference);
            for (ScoredPair pair : rankedForReference) {
                byInvoice.computeIfAbsent(pair.invoice().id(), ignored -> new ArrayList<>()).add(pair);
            }
        }
        byInvoice.replaceAll((ignored, pairs) -> pairs.stream()
                .sorted(Comparator.comparingDouble(ScoredPair::score).reversed()
                        .thenComparing(pair -> pair.reference().position())
                        .thenComparing(pair -> pair.reference().id()))
                .toList());
        return new RankedPairs(Map.copyOf(byReference), Map.copyOf(byInvoice));
    }

    private static BigDecimal margin(List<ScoredPair> rankedCandidates) {
        if (rankedCandidates.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double best = rankedCandidates.getFirst().score();
        double second = rankedCandidates.size() > 1 ? rankedCandidates.get(1).score() : 0.0;
        return BigDecimal.valueOf(best - second);
    }

    private static Map<String, List<MatchableLineItem>> groupByKey(
            Collection<MatchableLineItem> lines, Function<MatchableLineItem, String> keyExtractor) {
        Map<String, List<MatchableLineItem>> groups = new HashMap<>();
        for (MatchableLineItem line : lines) {
            String key = keyExtractor.apply(line);
            if (key != null && !key.isBlank()) {
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(line);
            }
        }
        return groups;
    }

    private static List<MatchableLineItem> orderedUniqueLines(List<MatchableLineItem> lines, String side) {
        Objects.requireNonNull(lines, side + " lines must not be null");
        List<MatchableLineItem> ordered = lines.stream()
                .map(line -> Objects.requireNonNull(line, side + " lines must not contain null"))
                .sorted(LINE_ORDER)
                .toList();
        Set<UUID> ids = new HashSet<>();
        for (MatchableLineItem line : ordered) {
            if (!ids.add(line.id())) {
                throw new IllegalArgumentException(side + " line identities must be unique");
            }
        }
        return ordered;
    }

    private static BigDecimal confidence(double score) {
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private static LineItemMatch createMatch(
            UUID analysisId,
            UUID referenceId,
            UUID invoiceId,
            LineMatchStatus status,
            LineMatchMethod method,
            BigDecimal confidence,
            Instant createdAt,
            Supplier<UUID> matchIdGenerator,
            Set<UUID> generatedMatchIds) {
        UUID matchId = Objects.requireNonNull(matchIdGenerator.get(), "matchIdGenerator returned null");
        if (!generatedMatchIds.add(matchId)) {
            throw new IllegalArgumentException("matchIdGenerator must return unique identifiers");
        }
        return new LineItemMatch(matchId, analysisId, referenceId, invoiceId, status, method,
                confidence, 0, null, createdAt, createdAt);
    }

    private static void addUnmatchedRows(
            UUID analysisId,
            List<MatchableLineItem> remaining,
            boolean referenceSide,
            Instant createdAt,
            Supplier<UUID> matchIdGenerator,
            Set<UUID> generatedMatchIds,
            List<LineItemMatch> matches) {
        for (MatchableLineItem line : remaining) {
            matches.add(createMatch(analysisId,
                    referenceSide ? line.id() : null,
                    referenceSide ? null : line.id(),
                    referenceSide ? LineMatchStatus.UNMATCHED_REFERENCE : LineMatchStatus.UNMATCHED_INVOICE,
                    LineMatchMethod.NONE, null, createdAt, matchIdGenerator, generatedMatchIds));
        }
    }

    private record ScoredPair(MatchableLineItem reference, MatchableLineItem invoice, double score) {
    }

    private record RankedPairs(
            Map<UUID, List<ScoredPair>> byReference,
            Map<UUID, List<ScoredPair>> byInvoice) {
    }
}
