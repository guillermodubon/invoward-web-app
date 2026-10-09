package io.github.guillermodubon.invoward.support.ai;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousLineMatchingInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchSuggestion;
import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic test-only matcher that performs no network I/O. */
public final class FakeAmbiguousLineMatcher implements AmbiguousLineMatcher {

    private final AtomicInteger calls = new AtomicInteger();
    private final CopyOnWriteArrayList<AmbiguousLineMatchingInput> inputs = new CopyOnWriteArrayList<>();
    private volatile Outcome outcome = Outcome.UNAVAILABLE;
    private volatile String candidateKey;

    @Override
    public Optional<AmbiguousMatchSuggestion> suggest(AmbiguousLineMatchingInput input) {
        calls.incrementAndGet();
        inputs.add(input);
        return switch (outcome) {
            case CANDIDATE -> Optional.of(new AmbiguousMatchSuggestion(candidateKey));
            case NO_MATCH -> Optional.of(new AmbiguousMatchSuggestion(AmbiguousMatchSuggestion.NO_MATCH));
            case INVALID_RESPONSE -> Optional.of(new AmbiguousMatchSuggestion("INVALID_CANDIDATE_KEY"));
            case UNAVAILABLE -> Optional.empty();
        };
    }

    public void configureCandidate(String candidateKey) {
        if (candidateKey == null || candidateKey.isBlank()) {
            throw new IllegalArgumentException("candidateKey must not be blank");
        }
        this.candidateKey = candidateKey;
        this.outcome = Outcome.CANDIDATE;
    }

    public void configureNoMatch() {
        outcome = Outcome.NO_MATCH;
        candidateKey = null;
    }

    public void configureUnavailable() {
        outcome = Outcome.UNAVAILABLE;
        candidateKey = null;
    }

    /** Configures a syntactically present key that cannot be one of the ephemeral C<n> input labels. */
    public void configureInvalidResponse() {
        outcome = Outcome.INVALID_RESPONSE;
        candidateKey = null;
    }

    public int callCount() {
        return calls.get();
    }

    public List<AmbiguousLineMatchingInput> inputs() {
        return List.copyOf(inputs);
    }

    public Optional<AmbiguousLineMatchingInput> lastInput() {
        int size = inputs.size();
        return size == 0 ? Optional.empty() : Optional.of(inputs.get(size - 1));
    }

    public void reset() {
        calls.set(0);
        inputs.clear();
        candidateKey = null;
        outcome = Outcome.UNAVAILABLE;
    }

    private enum Outcome {
        CANDIDATE,
        NO_MATCH,
        INVALID_RESPONSE,
        UNAVAILABLE
    }
}
