package io.github.guillermodubon.invoward.reconciliation.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Precomputed token/trigram index that limits expensive scoring to overlapping invoice candidates. */
public final class LineDescriptionCandidateIndex {

    private static final Comparator<Candidate> OVERLAP_ORDER = Comparator
            .comparingInt(Candidate::position)
            .thenComparing(Candidate::id);
    private static final Comparator<RankedCandidate> SCORE_ORDER = Comparator
            .comparingDouble(RankedCandidate::score)
            .reversed()
            .thenComparing(candidate -> candidate.candidate().position())
            .thenComparing(candidate -> candidate.candidate().id());

    private final LineDescriptionSimilarity similarity;
    private final int maxCandidates;
    private final Map<UUID, IndexedCandidate> candidatesById;
    private final Map<String, List<UUID>> invoiceIdsByToken;
    private final Map<String, List<UUID>> invoiceIdsByTrigram;

    public LineDescriptionCandidateIndex(
            Collection<Candidate> invoiceCandidates,
            LineDescriptionSimilarity similarity,
            int maxCandidates) {
        Objects.requireNonNull(invoiceCandidates, "invoiceCandidates must not be null");
        this.similarity = Objects.requireNonNull(similarity, "similarity must not be null");
        if (maxCandidates < 1 || maxCandidates > 50) {
            throw new IllegalArgumentException("maxCandidates must be between 1 and 50");
        }
        this.maxCandidates = maxCandidates;

        Map<UUID, IndexedCandidate> indexedCandidates = new HashMap<>();
        Map<String, List<UUID>> tokenIndex = new HashMap<>();
        Map<String, List<UUID>> trigramIndex = new HashMap<>();
        for (Candidate candidate : invoiceCandidates) {
            Objects.requireNonNull(candidate, "invoiceCandidates must not contain null");
            IndexedCandidate indexed = new IndexedCandidate(candidate, similarity.precompute(candidate.normalizedDescription()));
            if (indexedCandidates.putIfAbsent(candidate.id(), indexed) != null) {
                throw new IllegalArgumentException("invoice candidate identities must be unique");
            }
            indexFeatures(indexed, indexed.features().tokens(), tokenIndex);
            indexFeatures(indexed, indexed.features().characterTrigrams(), trigramIndex);
        }
        this.candidatesById = Map.copyOf(indexedCandidates);
        this.invoiceIdsByToken = immutableIndex(tokenIndex);
        this.invoiceIdsByTrigram = immutableIndex(trigramIndex);
    }

    public List<RankedCandidate> rank(String referenceNormalizedDescription) {
        LineDescriptionSimilarity.Features reference = similarity.precompute(referenceNormalizedDescription);
        Map<UUID, Integer> overlapCounts = new HashMap<>();
        addOverlaps(reference.tokens(), invoiceIdsByToken, overlapCounts);
        addOverlaps(reference.characterTrigrams(), invoiceIdsByTrigram, overlapCounts);

        List<UUID> shortlist = overlapCounts.keySet().stream()
                .sorted(Comparator.<UUID>comparingInt(overlapCounts::get).reversed()
                        .thenComparing(id -> candidatesById.get(id).candidate(), OVERLAP_ORDER))
                .limit(maxCandidates)
                .toList();

        List<RankedCandidate> ranked = new ArrayList<>(shortlist.size());
        for (UUID invoiceId : shortlist) {
            IndexedCandidate candidate = candidatesById.get(invoiceId);
            ranked.add(new RankedCandidate(candidate.candidate(), overlapCounts.get(invoiceId),
                    similarity.score(reference, candidate.features())));
        }
        ranked.sort(SCORE_ORDER);
        return List.copyOf(ranked);
    }

    private static void indexFeatures(
            IndexedCandidate candidate, Set<String> features, Map<String, List<UUID>> index) {
        for (String feature : features) {
            index.computeIfAbsent(feature, ignored -> new ArrayList<>()).add(candidate.candidate().id());
        }
    }

    private static Map<String, List<UUID>> immutableIndex(Map<String, List<UUID>> index) {
        return index.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private static void addOverlaps(Set<String> features, Map<String, List<UUID>> index, Map<UUID, Integer> counts) {
        for (String feature : features) {
            for (UUID candidateId : index.getOrDefault(feature, List.of())) {
                counts.merge(candidateId, 1, Integer::sum);
            }
        }
    }

    public record Candidate(UUID id, int position, String normalizedDescription) {

        public Candidate {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(normalizedDescription, "normalizedDescription must not be null");
            if (position < 0) {
                throw new IllegalArgumentException("position must not be negative");
            }
        }
    }

    public record RankedCandidate(Candidate candidate, int overlapCount, double score) {

        public RankedCandidate {
            Objects.requireNonNull(candidate, "candidate must not be null");
            if (overlapCount < 1 || !Double.isFinite(score) || score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("ranked candidate score and overlap must be valid");
            }
        }
    }

    private record IndexedCandidate(Candidate candidate, LineDescriptionSimilarity.Features features) {
    }
}
