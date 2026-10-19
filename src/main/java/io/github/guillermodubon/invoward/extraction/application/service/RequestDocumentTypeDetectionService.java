package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Requests asynchronous document classification and returns its persisted processing status. */
@Service
public class RequestDocumentTypeDetectionService {

    private final QueueDocumentTypeDetectionTransaction queueTransaction;
    private final AnalysisProcessingSignalPublisher signalPublisher;

    public RequestDocumentTypeDetectionService(
            QueueDocumentTypeDetectionTransaction queueTransaction,
            AnalysisProcessingSignalPublisher signalPublisher) {
        this.queueTransaction = Objects.requireNonNull(queueTransaction);
        this.signalPublisher = Objects.requireNonNull(signalPublisher);
    }

    public AnalysisStatusSnapshot request(UUID analysisId, AnalysisOwner owner) {
        AnalysisStatusSnapshot snapshot = queueTransaction.queue(analysisId, owner);
        if (snapshot.status() == AnalysisStatus.CLASSIFYING
                && snapshot.jobStatus() == AnalysisJobStatus.QUEUED
                && snapshot.currentStage() == AnalysisStatus.CLASSIFYING) {
            // The event adapter wakes the worker after the queue transaction commits.
            // Re-waking a queued command is safe and recovers missed best-effort signals.
            signalPublisher.request(snapshot.analysisId());
        }
        return snapshot;
    }
}
