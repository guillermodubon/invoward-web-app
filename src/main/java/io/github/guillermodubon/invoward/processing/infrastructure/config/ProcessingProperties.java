package io.github.guillermodubon.invoward.processing.infrastructure.config;

import io.github.guillermodubon.invoward.processing.application.port.ProcessingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "invoward.processing")
public record ProcessingProperties(
        @DefaultValue Executor executor,
        @DefaultValue("100") int recoveryBatchSize,
        @DefaultValue("3") int maxAttemptsPerStage,
        @DefaultValue("30s") Duration shutdownAwait) implements ProcessingPolicy {

    private static final int MAX_RECOVERY_BATCH_SIZE = 500;
    private static final int MAX_ATTEMPTS_PER_STAGE = 5;
    private static final Duration MIN_SHUTDOWN_AWAIT = Duration.ofSeconds(1);
    private static final Duration MAX_SHUTDOWN_AWAIT = Duration.ofMinutes(2);

    public ProcessingProperties {
        Objects.requireNonNull(executor, "Processing executor properties must be configured");
        requireRange(recoveryBatchSize, 1, MAX_RECOVERY_BATCH_SIZE,
                "invoward.processing.recovery-batch-size");
        requireRange(maxAttemptsPerStage, 1, MAX_ATTEMPTS_PER_STAGE,
                "invoward.processing.max-attempts-per-stage");
        if (shutdownAwait == null
                || shutdownAwait.compareTo(MIN_SHUTDOWN_AWAIT) < 0
                || shutdownAwait.compareTo(MAX_SHUTDOWN_AWAIT) > 0) {
            throw new IllegalArgumentException(
                    "invoward.processing.shutdown-await must be between 1s and 2m");
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String property) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(property + " must be between " + minimum + " and " + maximum);
        }
    }

    public record Executor(
            @DefaultValue("2") int corePoolSize,
            @DefaultValue("4") int maxPoolSize,
            @DefaultValue("32") int queueCapacity) {

        public Executor {
            requireRange(corePoolSize, 1, 8, "invoward.processing.executor.core-pool-size");
            if (maxPoolSize < corePoolSize || maxPoolSize > 16) {
                throw new IllegalArgumentException(
                        "invoward.processing.executor.max-pool-size must be at least core-pool-size and at most 16");
            }
            requireRange(queueCapacity, 1, 500, "invoward.processing.executor.queue-capacity");
        }
    }
}
