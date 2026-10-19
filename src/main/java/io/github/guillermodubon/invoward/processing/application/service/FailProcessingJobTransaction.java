package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingClaim;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingFailure;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Persists a classified failure only while its original locked stage attempt is still current. */
@Service
public class FailProcessingJobTransaction {

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final Clock clock;
    private final int maxAttemptsPerStage;

    public FailProcessingJobTransaction(
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
    public boolean fail(ProcessingClaim claim, ProcessingFailure failure) {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        Instant now = clock.instant();

        // Lock Analysis first, then Job; stale worker failures must not overwrite newer state.
        Analysis analysis = analysisRepository.findByIdForProcessingForUpdate(claim.analysisId()).orElse(null);
        if (analysis == null) {
            return false;
        }
        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(claim.analysisId()).orElse(null);
        if (job == null || !matchesClaim(analysis, job, claim)) {
            return false;
        }

        AnalysisJob failedJob = job.failCurrentAttempt(
                failure.code(), failure.internalMessage(), failure.retryableCandidate(), maxAttemptsPerStage, now);
        Analysis failedAnalysis = analysis.failProcessing(
                claim.stage(), failure.code(), failure.userMessage(), failedJob.retryable(), now);
        analysisRepository.update(failedAnalysis);
        analysisJobRepository.update(failedJob);
        return true;
    }

    private static boolean matchesClaim(Analysis analysis, AnalysisJob job, ProcessingClaim claim) {
        return analysis.owner().equals(claim.owner())
                && analysis.status() == claim.stage()
                && job.status() == AnalysisJobStatus.RUNNING
                && job.currentStage() == claim.stage()
                && job.attemptCount() == claim.attemptNumber();
    }
}
