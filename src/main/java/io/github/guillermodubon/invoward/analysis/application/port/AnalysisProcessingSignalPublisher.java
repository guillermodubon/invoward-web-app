package io.github.guillermodubon.invoward.analysis.application.port;

import java.util.UUID;

/** Publishes a wake-up signal when persisted processing work may be available. */
public interface AnalysisProcessingSignalPublisher {

    void request(UUID analysisId);
}
