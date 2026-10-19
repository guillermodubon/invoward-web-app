package io.github.guillermodubon.invoward.processing.application.port;

import java.util.UUID;

/** Receives best-effort wake-ups for durable processing jobs. */
public interface ProcessingWakePort {

    void wake(UUID analysisId);
}
