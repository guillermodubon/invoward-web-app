package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Requeues an eligible stage and signals processing only after its transaction succeeds. */
@Service
public class RetryAnalysisProcessingService {

    private final RetryAnalysisProcessingTransaction retryTransaction;
    private final AnalysisProcessingSignalPublisher signalPublisher;

    public RetryAnalysisProcessingService(
            RetryAnalysisProcessingTransaction retryTransaction,
            AnalysisProcessingSignalPublisher signalPublisher) {
        this.retryTransaction = Objects.requireNonNull(retryTransaction);
        this.signalPublisher = Objects.requireNonNull(signalPublisher);
    }

    public AnalysisStatusSnapshot retry(UUID analysisId, AnalysisOwner owner) {
        AnalysisStatusSnapshot snapshot = retryTransaction.retry(analysisId, owner);
        // The transaction bean commits before returning; a surrounding request transaction, if any,
        // is respected by the transactional event listener's AFTER_COMMIT phase.
        signalPublisher.request(snapshot.analysisId());
        return snapshot;
    }
}
