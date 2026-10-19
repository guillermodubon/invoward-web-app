package io.github.guillermodubon.invoward.processing.infrastructure.event;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** Adapts the Analysis-owned signal port to Spring's internal event mechanism. */
@Component
public class SpringAnalysisProcessingSignalPublisher implements AnalysisProcessingSignalPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public SpringAnalysisProcessingSignalPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    @Override
    public void request(UUID analysisId) {
        eventPublisher.publishEvent(new ProcessingRequestedEvent(analysisId));
    }
}
