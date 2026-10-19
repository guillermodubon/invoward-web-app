package io.github.guillermodubon.invoward.processing.application.port;

import io.github.guillermodubon.invoward.processing.application.model.ProcessingFailure;

import java.util.Optional;

/** Converts a stage exception to safe persisted failure data when it represents a job failure. */
public interface ProcessingFailureClassifier {

    /**
     * Returns empty for executor submission rejection, which leaves the queued job untouched.
     */
    Optional<ProcessingFailure> classify(RuntimeException exception);
}
