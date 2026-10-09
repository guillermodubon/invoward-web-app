package io.github.guillermodubon.invoward.reconciliation.application.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete deterministic match plan plus the ambiguous candidate groups eligible for AI. */
public record LineItemMatchingResult(
        MatchingPlan plan,
        List<AmbiguousMatchCandidates> ambiguousCandidates) {

    public LineItemMatchingResult {
        Objects.requireNonNull(plan, "plan must not be null");
        ambiguousCandidates = List.copyOf(ambiguousCandidates);
        HashSet<UUID> referenceIds = new HashSet<>();
        for (AmbiguousMatchCandidates candidates : ambiguousCandidates) {
            Objects.requireNonNull(candidates, "ambiguousCandidates must not contain null");
            if (!referenceIds.add(candidates.referenceLine().id())) {
                throw new IllegalArgumentException("each reference line can have one ambiguous candidate group");
            }
        }
    }
}
