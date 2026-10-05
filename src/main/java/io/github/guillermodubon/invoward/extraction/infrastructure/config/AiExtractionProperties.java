package io.github.guillermodubon.invoward.extraction.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "invoward.ai.extraction")
public record AiExtractionProperties(
        @DefaultValue("24h") Duration cacheTtl,
        @DefaultValue("500") int maxLineItems,
        @DefaultValue("4000") int maxSourceTextCodePoints) {

    private static final Duration MAX_CACHE_TTL = Duration.ofHours(24);
    private static final int MAX_LINE_ITEMS = 500;
    private static final int MIN_SOURCE_TEXT_CODE_POINTS = 200;
    private static final int MAX_SOURCE_TEXT_CODE_POINTS = 4000;

    public AiExtractionProperties {
        Objects.requireNonNull(cacheTtl, "AI_EXTRACTION_CACHE_TTL must be configured");
        if (cacheTtl.isZero() || cacheTtl.isNegative() || cacheTtl.compareTo(MAX_CACHE_TTL) > 0) {
            throw new IllegalArgumentException("AI_EXTRACTION_CACHE_TTL must be greater than 0 and at most 24h");
        }
        if (maxLineItems < 1 || maxLineItems > MAX_LINE_ITEMS) {
            throw new IllegalArgumentException("AI_EXTRACTION_MAX_LINE_ITEMS must be between 1 and 500");
        }
        if (maxSourceTextCodePoints < MIN_SOURCE_TEXT_CODE_POINTS
                || maxSourceTextCodePoints > MAX_SOURCE_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "AI_EXTRACTION_MAX_SOURCE_TEXT_CODE_POINTS must be between 200 and 4000");
        }
    }
}
