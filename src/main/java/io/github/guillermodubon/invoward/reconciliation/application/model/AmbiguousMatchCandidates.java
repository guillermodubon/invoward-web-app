package io.github.guillermodubon.invoward.reconciliation.application.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic fuzzy candidates eligible for later optional AI assistance. */
public record AmbiguousMatchCandidates(MatchableLineItem referenceLine, List<Candidate> candidates) {

    public AmbiguousMatchCandidates {
        Objects.requireNonNull(referenceLine, "referenceLine must not be null");
        candidates = List.copyOf(candidates);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("ambiguous candidates must not be empty");
        }
        Set<UUID> candidateIds = new HashSet<>();
        for (Candidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidates must not contain null");
            if (!candidateIds.add(candidate.lineItem().id())) {
                throw new IllegalArgumentException("ambiguous candidate identities must be unique");
            }
        }
    }

    public record Candidate(MatchableLineItem lineItem, double score) {

        public Candidate {
            Objects.requireNonNull(lineItem, "lineItem must not be null");
            if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("candidate score must be between 0 and 1");
            }
        }
    }
}
