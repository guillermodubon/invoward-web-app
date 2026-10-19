package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Queues extraction without invoking any provider on the requesting thread. */
@Service
public class RequestExtractionService {

    private final QueueExtractionTransaction queueTransaction;
    private final AnalysisProcessingSignalPublisher signalPublisher;

    public RequestExtractionService(
            QueueExtractionTransaction queueTransaction,
            AnalysisProcessingSignalPublisher signalPublisher) {
        this.queueTransaction = Objects.requireNonNull(queueTransaction);
        this.signalPublisher = Objects.requireNonNull(signalPublisher);
    }

    public AnalysisStatusSnapshot request(
            UUID analysisId,
            AnalysisOwner owner,
            ExtractionRequestTypes requestedTypes) {
        AnalysisStatusSnapshot snapshot = queueTransaction.queue(analysisId, owner, requestedTypes);
        if (snapshot.status() == AnalysisStatus.EXTRACTING
                && snapshot.jobStatus() == AnalysisJobStatus.QUEUED
                && snapshot.currentStage() == AnalysisStatus.EXTRACTING) {
            // The transactional event adapter delivers this wake-up only after commit.
            // Re-waking an already queued request is safe and helps recover missed wake-ups.
            signalPublisher.request(snapshot.analysisId());
        }
        return snapshot;
    }
}
