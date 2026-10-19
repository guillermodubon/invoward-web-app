package io.github.guillermodubon.invoward.processing.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;

import java.util.Objects;
import java.util.UUID;

/** Immutable proof of the persisted stage attempt owned by one processing worker. */
public record ProcessingClaim(
        UUID analysisId,
        AnalysisOwner owner,
        AnalysisStatus stage,
        int attemptNumber) {

    public ProcessingClaim {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        if (!isMachineStage(stage)) {
            throw new IllegalArgumentException("stage must be a machine stage");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
    }

    private static boolean isMachineStage(AnalysisStatus stage) {
        return stage == AnalysisStatus.CLASSIFYING
                || stage == AnalysisStatus.EXTRACTING
                || stage == AnalysisStatus.MATCHING
                || stage == AnalysisStatus.RECONCILING
                || stage == AnalysisStatus.GENERATING_REPORT;
    }
}
