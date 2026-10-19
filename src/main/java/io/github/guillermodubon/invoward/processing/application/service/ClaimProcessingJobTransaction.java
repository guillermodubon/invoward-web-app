package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingClaim;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomically claims one durable machine-stage attempt before any external work starts. */
@Service
public class ClaimProcessingJobTransaction {

    private static final String ANALYSIS_EXPIRED = "ANALYSIS_EXPIRED";
    private static final String EXPIRED_INTERNAL_MESSAGE = "Guest analysis expired before processing started.";
    private static final String EXPIRED_USER_MESSAGE = "This guest analysis has expired.";

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final Clock clock;
    private final int maxAttemptsPerStage;

    public ClaimProcessingJobTransaction(
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
    public Optional<ProcessingClaim> claim(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Instant now = clock.instant();

        // Keep lock order consistent with every other processing state transition.
        Analysis analysis = analysisRepository.findByIdForProcessingForUpdate(analysisId).orElse(null);
        if (analysis == null) {
            return Optional.empty();
        }
        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId).orElse(null);
        if (job == null || !isClaimable(analysis, job)) {
            return Optional.empty();
        }

        if (analysis.owner() instanceof GuestSessionOwner guestOwner
                && !guestOwner.expiresAt().isAfter(now)) {
            failExpiredQueuedAnalysis(analysis, job, now);
            return Optional.empty();
        }

        AnalysisJob started = analysisJobRepository.update(job.startQueuedAttempt(now, maxAttemptsPerStage));
        return Optional.of(new ProcessingClaim(
                analysis.id(), analysis.owner(), started.currentStage(), started.attemptCount()));
    }

    private void failExpiredQueuedAnalysis(Analysis analysis, AnalysisJob job, Instant now) {
        Analysis failedAnalysis = analysis.failProcessing(
                job.currentStage(), ANALYSIS_EXPIRED, EXPIRED_USER_MESSAGE, false, now);
        AnalysisJob failedJob = job.failQueuedProcessing(ANALYSIS_EXPIRED, EXPIRED_INTERNAL_MESSAGE, now);
        analysisRepository.update(failedAnalysis);
        analysisJobRepository.update(failedJob);
    }

    private boolean isClaimable(Analysis analysis, AnalysisJob job) {
        return job.status() == AnalysisJobStatus.QUEUED
                && isMachineStage(job.currentStage())
                && analysis.status() == job.currentStage()
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
