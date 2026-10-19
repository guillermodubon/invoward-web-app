package io.github.guillermodubon.invoward.processing.infrastructure.event;

import java.util.Objects;
import java.util.UUID;

/** Internal wake-up event; the persisted job remains the source of truth. */
public record ProcessingRequestedEvent(UUID analysisId) {

    public ProcessingRequestedEvent {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
    }
}
