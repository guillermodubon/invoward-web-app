package io.github.guillermodubon.invoward.analysis.domain;

public enum AnalysisJobStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_USER,
    COMPLETED,
    FAILED
}
