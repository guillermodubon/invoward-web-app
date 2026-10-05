package io.github.guillermodubon.invoward.extraction.infrastructure.ai;

import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import io.github.guillermodubon.invoward.extraction.infrastructure.ai.disabled.DisabledDocumentIntelligence;
import io.github.guillermodubon.invoward.extraction.infrastructure.ai.gemini.SpringAiGeminiDocumentIntelligence;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DocumentIntelligenceProviderWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProviderComponents.class);

    @Test
    void disabledProviderIsTheOnlyBeanByDefaultAndNeedsNoGeminiSecrets() {
        contextRunner.withPropertyValues("spring.ai.model.chat=none")
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentIntelligence.class);
                    assertThat(context.getBean(DocumentIntelligence.class))
                            .isInstanceOf(DisabledDocumentIntelligence.class);
                });
    }

    @Test
    void googleProviderSelectsTheRealSpringAiAdapterAndUsesConfiguredModel() {
        contextRunner.withPropertyValues(
                        "spring.ai.model.chat=google-genai",
                        "spring.ai.google.genai.chat.model=deployment-selected-model")
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentIntelligence.class);
                    assertThat(context.getBean(DocumentIntelligence.class))
                            .isInstanceOf(SpringAiGeminiDocumentIntelligence.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({DisabledDocumentIntelligence.class, SpringAiGeminiDocumentIntelligence.class})
    static class ProviderComponents {
        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }
    }
}
