package io.github.guillermodubon.invoward.extraction.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AiExtractionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AiExtractionPropertiesConfiguration.class);

    @Test
    void bindsSafeDefaultsAndApprovedLimits() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AiExtractionProperties properties = context.getBean(AiExtractionProperties.class);
            assertEquals(Duration.ofHours(24), properties.cacheTtl());
            assertEquals(500, properties.maxLineItems());
            assertEquals(4000, properties.maxSourceTextCodePoints());
            assertEquals("none", context.getEnvironment().getProperty("spring.ai.model.chat"));
            assertEquals("", context.getEnvironment().getProperty("spring.ai.google.genai.api-key"));
            assertEquals("", context.getEnvironment().getProperty("spring.ai.google.genai.chat.model"));
            assertEquals("0", context.getEnvironment().getProperty("spring.ai.google.genai.chat.temperature"));
            assertEquals("1", context.getEnvironment().getProperty("spring.ai.google.genai.chat.candidate-count"));
            assertEquals("false", context.getEnvironment()
                    .getProperty("spring.ai.google.genai.chat.google-search-retrieval"));
            assertEquals("NONE", context.getEnvironment()
                    .getProperty("spring.ai.google.genai.chat.tool-choice.mode"));
        });
    }

    @Test
    void bindsConfiguredValuesWithinApprovedBounds() {
        contextRunner.withPropertyValues(
                "invoward.ai.extraction.cache-ttl=12h",
                "invoward.ai.extraction.max-line-items=250",
                "invoward.ai.extraction.max-source-text-code-points=1200").run(context -> {
            assertThat(context).hasNotFailed();
            AiExtractionProperties properties = context.getBean(AiExtractionProperties.class);
            assertEquals(Duration.ofHours(12), properties.cacheTtl());
            assertEquals(250, properties.maxLineItems());
            assertEquals(1200, properties.maxSourceTextCodePoints());
        });
    }

    @Test
    void rejectsOutOfRangeConfigurationAtBindingTime() {
        for (String ttl : List.of("0s", "-1s", "24h1s")) {
            assertConfigurationFails("invoward.ai.extraction.cache-ttl=" + ttl);
        }
        for (String maxLines : List.of("0", "501")) {
            assertConfigurationFails("invoward.ai.extraction.max-line-items=" + maxLines);
        }
        for (String sourceLimit : List.of("199", "4001")) {
            assertConfigurationFails("invoward.ai.extraction.max-source-text-code-points=" + sourceLimit);
        }
    }

    private void assertConfigurationFails(String property) {
        contextRunner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiExtractionProperties.class)
    static class AiExtractionPropertiesConfiguration {
    }
}
