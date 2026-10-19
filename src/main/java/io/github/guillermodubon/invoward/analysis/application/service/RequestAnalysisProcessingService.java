package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Requests persisted processing work and signals the worker without performing that work inline. */
@Service
public class RequestAnalysisProcessingService {

    private final RequestAnalysisProcessingTransaction requestTransaction;
    private final AnalysisProcessingSignalPublisher signalPublisher;

    public RequestAnalysisProcessingService(
            RequestAnalysisProcessingTransaction requestTransaction,
            AnalysisProcessingSignalPublisher signalPublisher) {
        this.requestTransaction = Objects.requireNonNull(requestTransaction);
        this.signalPublisher = Objects.requireNonNull(signalPublisher);
    }

    public AnalysisStatusSnapshot request(
            UUID analysisId,
            AnalysisOwner owner,
            AnalysisStatus requestedStage) {
        AnalysisStatusSnapshot snapshot = requestTransaction.request(analysisId, owner, requestedStage);
        if (snapshot.status() == requestedStage
                && snapshot.currentStage() == requestedStage
                && snapshot.jobStatus() == AnalysisJobStatus.QUEUED) {
            // Re-waking an existing queued job is idempotent and recovers a missed best-effort signal.
            signalPublisher.request(snapshot.analysisId());
        }
        return snapshot;
    }
}
