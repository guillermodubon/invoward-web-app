package io.github.guillermodubon.invoward.processing.application.port;

/** Runtime policy values required by processing use cases. */
public interface ProcessingPolicy {

    int recoveryBatchSize();

    int maxAttemptsPerStage();
}
