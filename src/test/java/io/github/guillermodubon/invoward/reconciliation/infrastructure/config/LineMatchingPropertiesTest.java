package io.github.guillermodubon.invoward.reconciliation.infrastructure.config;

import io.github.guillermodubon.invoward.reconciliation.application.service.LineItemMatchingEngine;
import io.github.guillermodubon.invoward.reconciliation.application.service.AmbiguousLineMatchingAssistant;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.ai.disabled.DisabledAmbiguousLineMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LineMatchingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LineMatchingPropertiesConfiguration.class);

    @Test
    void bindsApprovedDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            LineMatchingProperties properties = context.getBean(LineMatchingProperties.class);
            assertEquals(new BigDecimal("0.90"), properties.fuzzy().autoThreshold());
            assertEquals(new BigDecimal("0.65"), properties.fuzzy().ambiguousThreshold());
            assertEquals(new BigDecimal("0.08"), properties.fuzzy().minMargin());
            assertEquals(20, properties.fuzzy().maxCandidates());
            assertEquals(5, properties.ai().maxCandidates());
        });
    }

    @Test
    void bindsExplicitValuesWithinApprovedBounds() {
        contextRunner.withPropertyValues(
                "invoward.matching.fuzzy.auto-threshold=0.95",
                "invoward.matching.fuzzy.ambiguous-threshold=0.70",
                "invoward.matching.fuzzy.min-margin=0.10",
                "invoward.matching.fuzzy.max-candidates=25",
                "invoward.matching.ai.max-candidates=4").run(context -> {
            assertThat(context).hasNotFailed();
            LineMatchingProperties properties = context.getBean(LineMatchingProperties.class);
            assertEquals(new BigDecimal("0.95"), properties.fuzzy().autoThreshold());
            assertEquals(new BigDecimal("0.70"), properties.fuzzy().ambiguousThreshold());
            assertEquals(new BigDecimal("0.10"), properties.fuzzy().minMargin());
            assertEquals(25, properties.fuzzy().maxCandidates());
            assertEquals(4, properties.ai().maxCandidates());
        });
    }

    @Test
    void createsDeterministicEngineFromBoundFuzzyProperties() {
        contextRunner.withUserConfiguration(LineMatchingConfiguration.class, DisabledAmbiguousLineMatcher.class)
                .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LineItemMatchingEngine.class);
            assertThat(context).hasSingleBean(AmbiguousLineMatchingAssistant.class);
        });
    }

    @Test
    void rejectsInvalidThresholdMarginAndCandidateValues() {
        for (String[] properties : List.<String[]>of(
                new String[]{"invoward.matching.fuzzy.auto-threshold=1.01"},
                new String[]{"invoward.matching.fuzzy.auto-threshold=0.65",
                        "invoward.matching.fuzzy.ambiguous-threshold=0.65"},
                new String[]{"invoward.matching.fuzzy.ambiguous-threshold=0"},
                new String[]{"invoward.matching.fuzzy.min-margin=-0.01"},
                new String[]{"invoward.matching.fuzzy.min-margin=0.51"},
                new String[]{"invoward.matching.fuzzy.max-candidates=0"},
                new String[]{"invoward.matching.fuzzy.max-candidates=51"},
                new String[]{"invoward.matching.ai.max-candidates=1"},
                new String[]{"invoward.matching.ai.max-candidates=11"},
                new String[]{"invoward.matching.fuzzy.max-candidates=3",
                        "invoward.matching.ai.max-candidates=4"})) {
            contextRunner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LineMatchingProperties.class)
    static class LineMatchingPropertiesConfiguration {
    }
}
