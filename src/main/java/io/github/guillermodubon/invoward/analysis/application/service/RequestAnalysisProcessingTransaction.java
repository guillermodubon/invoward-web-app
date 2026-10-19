package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisProcessingNotAllowedException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Owner-scoped, idempotent queue transaction for a persisted Analysis machine stage. */
@Service
public class RequestAnalysisProcessingTransaction {

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final Clock clock;

    public RequestAnalysisProcessingTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public AnalysisStatusSnapshot request(
            UUID analysisId,
            AnalysisOwner owner,
            AnalysisStatus requestedStage) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        requireSupportedStage(requestedStage);

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id())
                .orElseThrow(AnalysisProcessingNotAllowedException::new);

        if (analysis.status() == AnalysisStatus.FAILED || job.status() == AnalysisJobStatus.FAILED) {
            throw new AnalysisProcessingNotAllowedException();
        }

        if (analysis.status() == requestedStage) {
            return requestCurrentStage(analysis, job, requestedStage, now);
        }
        if (hasPassedStage(requestedStage, analysis.status()) && isConsistentWorkflow(analysis, job)) {
            return snapshot(analysis, job);
        }
        throw new AnalysisProcessingNotAllowedException();
    }

    private AnalysisStatusSnapshot requestCurrentStage(
            Analysis analysis,
            AnalysisJob job,
            AnalysisStatus requestedStage,
            Instant now) {
        if (job.currentStage() != requestedStage) {
            throw new AnalysisProcessingNotAllowedException();
        }
        if (job.status() == AnalysisJobStatus.QUEUED || job.status() == AnalysisJobStatus.RUNNING) {
            return snapshot(analysis, job);
        }
        if (job.status() == AnalysisJobStatus.WAITING_FOR_USER) {
            AnalysisJob queued = analysisJobRepository.update(job.queueLegacyMachineStage(now));
            return snapshot(analysis, queued);
        }
        throw new AnalysisProcessingNotAllowedException();
    }

    private static boolean isConsistentWorkflow(Analysis analysis, AnalysisJob job) {
        if (analysis.status() == AnalysisStatus.COMPLETED) {
            return job.status() == AnalysisJobStatus.COMPLETED
                    && job.currentStage() == AnalysisStatus.COMPLETED;
        }
        return job.currentStage() == analysis.status()
                && (job.status() == AnalysisJobStatus.QUEUED
                        || job.status() == AnalysisJobStatus.RUNNING
                        || job.status() == AnalysisJobStatus.WAITING_FOR_USER);
    }

    private static boolean hasPassedStage(AnalysisStatus requestedStage, AnalysisStatus currentStatus) {
        return switch (requestedStage) {
            case CLASSIFYING -> switch (currentStatus) {
                case EXTRACTING, AWAITING_CONFIRMATION, MATCHING, AWAITING_MATCH_REVIEW,
                        RECONCILING, GENERATING_REPORT, COMPLETED -> true;
                default -> false;
            };
            case RECONCILING -> currentStatus == AnalysisStatus.GENERATING_REPORT
                    || currentStatus == AnalysisStatus.COMPLETED;
            case GENERATING_REPORT -> currentStatus == AnalysisStatus.COMPLETED;
            default -> false;
        };
    }

    private static void requireSupportedStage(AnalysisStatus requestedStage) {
        if (requestedStage != AnalysisStatus.CLASSIFYING
                && requestedStage != AnalysisStatus.RECONCILING
                && requestedStage != AnalysisStatus.GENERATING_REPORT) {
            throw new AnalysisProcessingNotAllowedException();
        }
    }

    private static AnalysisStatusSnapshot snapshot(Analysis analysis, AnalysisJob job) {
        Instant updatedAt = analysis.updatedAt().isAfter(job.updatedAt())
                ? analysis.updatedAt()
                : job.updatedAt();
        return new AnalysisStatusSnapshot(
                analysis.id(), analysis.status(), job.status(), job.currentStage(),
                analysis.retryable(), analysis.failureCode(), analysis.failureUserMessage(), updatedAt);
    }
}
