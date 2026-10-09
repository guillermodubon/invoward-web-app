package io.github.guillermodubon.invoward.reconciliation.infrastructure.config;

import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import io.github.guillermodubon.invoward.reconciliation.application.service.AmbiguousLineMatchingAssistant;
import io.github.guillermodubon.invoward.reconciliation.application.service.LineItemMatchingEngine;
import io.github.guillermodubon.invoward.reconciliation.domain.LineDescriptionSimilarity;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemCodeNormalizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LineMatchingProperties.class)
public class LineMatchingConfiguration {

    @Bean
    LineItemMatchingEngine lineItemMatchingEngine(LineMatchingProperties properties) {
        LineMatchingProperties.Fuzzy fuzzy = properties.fuzzy();
        return new LineItemMatchingEngine(
                new LineItemCodeNormalizer(),
                new LineDescriptionSimilarity(),
                fuzzy.autoThreshold(),
                fuzzy.ambiguousThreshold(),
                fuzzy.minMargin(),
                fuzzy.maxCandidates());
    }

    @Bean
    AmbiguousLineMatchingAssistant ambiguousLineMatchingAssistant(
            AmbiguousLineMatcher matcher,
            LineMatchingProperties properties) {
        return new AmbiguousLineMatchingAssistant(matcher, properties.ai().maxCandidates(), UUID::randomUUID);
    }
}
