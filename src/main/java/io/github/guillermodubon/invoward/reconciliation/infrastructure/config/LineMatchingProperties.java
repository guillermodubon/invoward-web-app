package io.github.guillermodubon.invoward.reconciliation.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.util.Objects;

@ConfigurationProperties(prefix = "invoward.matching")
public record LineMatchingProperties(
        @DefaultValue Fuzzy fuzzy,
        @DefaultValue Ai ai) {

    public LineMatchingProperties {
        Objects.requireNonNull(fuzzy, "matching fuzzy properties must not be null");
        Objects.requireNonNull(ai, "matching AI properties must not be null");
        if (ai.maxCandidates() > fuzzy.maxCandidates()) {
            throw new IllegalArgumentException("MATCHING_AI_MAX_CANDIDATES must not exceed fuzzy max candidates");
        }
    }

    public record Fuzzy(
            @DefaultValue("0.90") BigDecimal autoThreshold,
            @DefaultValue("0.65") BigDecimal ambiguousThreshold,
            @DefaultValue("0.08") BigDecimal minMargin,
            @DefaultValue("20") int maxCandidates) {

        private static final BigDecimal ONE = BigDecimal.ONE;
        private static final BigDecimal MAX_MARGIN = new BigDecimal("0.50");

        public Fuzzy {
            Objects.requireNonNull(autoThreshold, "MATCHING_FUZZY_AUTO_THRESHOLD must be configured");
            Objects.requireNonNull(ambiguousThreshold, "MATCHING_FUZZY_AMBIGUOUS_THRESHOLD must be configured");
            Objects.requireNonNull(minMargin, "MATCHING_FUZZY_MIN_MARGIN must be configured");
            if (ambiguousThreshold.signum() <= 0
                    || ambiguousThreshold.compareTo(autoThreshold) >= 0
                    || autoThreshold.compareTo(ONE) > 0) {
                throw new IllegalArgumentException(
                        "matching thresholds must satisfy 0 < ambiguous threshold < auto threshold <= 1");
            }
            if (minMargin.signum() < 0 || minMargin.compareTo(MAX_MARGIN) > 0) {
                throw new IllegalArgumentException("MATCHING_FUZZY_MIN_MARGIN must be between 0 and 0.50");
            }
            if (maxCandidates < 1 || maxCandidates > 50) {
                throw new IllegalArgumentException("MATCHING_FUZZY_MAX_CANDIDATES must be between 1 and 50");
            }
        }
    }

    public record Ai(@DefaultValue("5") int maxCandidates) {

        public Ai {
            if (maxCandidates < 2 || maxCandidates > 10) {
                throw new IllegalArgumentException("MATCHING_AI_MAX_CANDIDATES must be between 2 and 10");
            }
        }
    }
}
