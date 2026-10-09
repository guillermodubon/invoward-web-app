package io.github.guillermodubon.invoward.reconciliation.infrastructure.ai.gemini;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiLineMatchStructuredOutputConvertersTest {

    @Test
    void candidateKeyConverterAcceptsListedCandidateAndNoMatchShapes() {
        BeanOutputConverter<GeminiLineMatchStructuredOutput.CandidateKey> converter =
                GeminiLineMatchStructuredOutputConverters.candidateKey();

        assertThat(converter.convert("{\"candidateKey\":\"C2\"}").candidateKey()).isEqualTo("C2");
        assertThat(converter.convert("{\"candidateKey\":\"NO_MATCH\"}").candidateKey()).isEqualTo("NO_MATCH");
    }

    @Test
    void structuredOutputContainsOnlyTheCandidateKey() {
        BeanOutputConverter<GeminiLineMatchStructuredOutput.CandidateKey> converter =
                GeminiLineMatchStructuredOutputConverters.candidateKey();

        assertThat(GeminiLineMatchStructuredOutput.CandidateKey.class.getRecordComponents())
                .extracting("name")
                .containsExactly("candidateKey");
        assertThat(converter.getJsonSchema())
                .contains("candidateKey")
                .doesNotContain("explanation", "reasoning", "confidence", "referenceId", "invoiceId");
    }
}
