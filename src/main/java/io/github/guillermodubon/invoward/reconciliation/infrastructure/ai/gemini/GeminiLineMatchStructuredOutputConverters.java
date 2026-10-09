package io.github.guillermodubon.invoward.reconciliation.infrastructure.ai.gemini;

import org.springframework.ai.converter.BeanOutputConverter;

/** Creates the typed structured-output converter used by the Gemini matching adapter. */
public final class GeminiLineMatchStructuredOutputConverters {

    private GeminiLineMatchStructuredOutputConverters() {
    }

    public static BeanOutputConverter<GeminiLineMatchStructuredOutput.CandidateKey> candidateKey() {
        return new BeanOutputConverter<>(GeminiLineMatchStructuredOutput.CandidateKey.class);
    }
}
