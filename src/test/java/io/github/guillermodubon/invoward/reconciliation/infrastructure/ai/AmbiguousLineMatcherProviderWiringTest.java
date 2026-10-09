package io.github.guillermodubon.invoward.reconciliation.infrastructure.ai;

import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.ai.disabled.DisabledAmbiguousLineMatcher;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.ai.gemini.SpringAiGeminiAmbiguousLineMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AmbiguousLineMatcherProviderWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MatcherProviderConfiguration.class);

    @Test
    void disabledProviderIsSelectedWhenChatModelIsNone() {
        contextRunner.withPropertyValues("spring.ai.model.chat=none")
                .run(context -> {
                    assertThat(context).hasSingleBean(AmbiguousLineMatcher.class);
                    assertThat(context.getBean(AmbiguousLineMatcher.class))
                            .isInstanceOf(DisabledAmbiguousLineMatcher.class);
                });
    }

    @Test
    void disabledProviderIsTheSafeDefaultWhenChatModelPropertyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AmbiguousLineMatcher.class);
            assertThat(context.getBean(AmbiguousLineMatcher.class))
                    .isInstanceOf(DisabledAmbiguousLineMatcher.class);
        });
    }

    @Test
    void googleGenaiSelectsTheGeminiAdapterWithoutRegisteringTheDisabledProvider() {
        contextRunner
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "spring.ai.model.chat=google-genai",
                        "spring.ai.google.genai.chat.model=configured-test-model")
                .run(context -> {
                    assertThat(context).hasSingleBean(AmbiguousLineMatcher.class);
                    assertThat(context.getBean(AmbiguousLineMatcher.class))
                            .isInstanceOf(SpringAiGeminiAmbiguousLineMatcher.class);
                    assertThat(context).doesNotHaveBean(DisabledAmbiguousLineMatcher.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({DisabledAmbiguousLineMatcher.class, SpringAiGeminiAmbiguousLineMatcher.class})
    static class MatcherProviderConfiguration {
    }
}
