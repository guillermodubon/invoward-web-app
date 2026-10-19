package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.processing.application.exception.ProcessingRetryNotAllowedException;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Atomically requeues a retryable failed stage while preserving its prior attempt count. */
@Service
public class RetryAnalysisProcessingTransaction {

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final Clock clock;
    private final int maxAttemptsPerStage;

    public RetryAnalysisProcessingTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            Clock clock,
            ProcessingPolicy policy) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.clock = Objects.requireNonNull(clock);
        this.maxAttemptsPerStage = Objects.requireNonNull(policy).maxAttemptsPerStage();
    }

    @Transactional
    public AnalysisStatusSnapshot retry(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Instant now = clock.instant();

        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId)
                .orElseThrow(ProcessingRetryNotAllowedException::new);
        if (!isRetryablePair(analysis, job)) {
            throw new ProcessingRetryNotAllowedException();
        }

        Analysis retriedAnalysis = analysisRepository.update(analysis.retryProcessing(job.currentStage(), now));
        AnalysisJob queuedJob = analysisJobRepository.update(job.retryFailedStage(now, maxAttemptsPerStage));
        return new AnalysisStatusSnapshot(
                retriedAnalysis.id(),
                retriedAnalysis.status(),
                queuedJob.status(),
                queuedJob.currentStage(),
                retriedAnalysis.retryable(),
                retriedAnalysis.failureCode(),
                retriedAnalysis.failureUserMessage(),
                retriedAnalysis.updatedAt());
    }

    private boolean isRetryablePair(Analysis analysis, AnalysisJob job) {
        return analysis.status() == AnalysisStatus.FAILED
                && analysis.retryable()
                && job.status() == AnalysisJobStatus.FAILED
                && job.retryable()
                && isMachineStage(job.currentStage())
                && job.attemptCount() < maxAttemptsPerStage;
    }

    private static boolean isMachineStage(AnalysisStatus stage) {
        return stage == AnalysisStatus.CLASSIFYING
                || stage == AnalysisStatus.EXTRACTING
                || stage == AnalysisStatus.MATCHING
                || stage == AnalysisStatus.RECONCILING
                || stage == AnalysisStatus.GENERATING_REPORT;
    }
}
