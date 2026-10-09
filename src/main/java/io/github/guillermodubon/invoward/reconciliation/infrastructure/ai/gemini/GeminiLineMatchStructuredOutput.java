package io.github.guillermodubon.invoward.reconciliation.infrastructure.ai.gemini;

/** Typed provider response. Its candidate key remains untrusted until resolved against the input labels. */
public final class GeminiLineMatchStructuredOutput {

    private GeminiLineMatchStructuredOutput() {
    }

    public record CandidateKey(String candidateKey) {
    }
}
