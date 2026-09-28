package io.github.guillermodubon.invoward.analysis.domain;

public enum AnalysisStatus {
    CREATED,
    UPLOADING,
    CLASSIFYING,
    EXTRACTING,
    AWAITING_CONFIRMATION,
    MATCHING,
    AWAITING_MATCH_REVIEW,
    RECONCILING,
    GENERATING_REPORT,
    COMPLETED,
    FAILED
}
