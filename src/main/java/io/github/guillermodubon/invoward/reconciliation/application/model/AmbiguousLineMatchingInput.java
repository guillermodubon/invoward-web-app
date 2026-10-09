package io.github.guillermodubon.invoward.reconciliation.application.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Minimal, provider-neutral candidate data; intentionally excludes IDs, amounts and evidence. */
public record AmbiguousLineMatchingInput(Line reference, List<Line> invoiceCandidates) {

    private static final int MAX_CANDIDATES = 10;

    public AmbiguousLineMatchingInput {
        Objects.requireNonNull(reference, "reference must not be null");
        invoiceCandidates = List.copyOf(invoiceCandidates);
        if (!"REF".equals(reference.label())) {
            throw new IllegalArgumentException("reference label must be REF");
        }
        if (invoiceCandidates.isEmpty() || invoiceCandidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("invoice candidate count must be between 1 and 10");
        }

        Set<String> labels = new HashSet<>();
        for (Line candidate : invoiceCandidates) {
            Objects.requireNonNull(candidate, "invoiceCandidates must not contain null");
            if (!candidate.isInvoiceCandidateLabel()) {
                throw new IllegalArgumentException("invoice candidate labels must use C followed by a positive number");
            }
            if (!labels.add(candidate.label())) {
                throw new IllegalArgumentException("invoice candidate labels must be unique");
            }
        }
    }

    @Override
    public String toString() {
        return "AmbiguousLineMatchingInput[reference=REF, invoiceCandidateCount=" + invoiceCandidates.size() + "]";
    }

    /** A line view safe to send to a matcher: only ephemeral label and non-financial identity fields. */
    public record Line(String label, String itemCode, String description, String unit) {

        private static final Pattern CANDIDATE_LABEL = Pattern.compile("C[1-9][0-9]*");

        public Line {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(description, "description must not be null");
            if (description.isBlank()) {
                throw new IllegalArgumentException("description must not be blank");
            }
        }

        private boolean isInvoiceCandidateLabel() {
            return CANDIDATE_LABEL.matcher(label).matches();
        }

        @Override
        public String toString() {
            return "Line[label=" + label + "]";
        }
    }
}
