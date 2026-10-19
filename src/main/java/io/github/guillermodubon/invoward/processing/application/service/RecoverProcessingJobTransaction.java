package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Recovers one Analysis/job pair using the standard Analysis-then-Job lock order. */
@Service
public class RecoverProcessingJobTransaction {

    private static final String ANALYSIS_EXPIRED = "ANALYSIS_EXPIRED";
    private static final String EXPIRED_INTERNAL_MESSAGE = "Guest analysis expired before processing resumed.";
    private static final String EXPIRED_USER_MESSAGE = "This guest analysis has expired.";
    private static final String RECOVERY_LIMIT_EXCEEDED = "PROCESSING_RECOVERY_LIMIT_EXCEEDED";
    private static final String RECOVERY_LIMIT_INTERNAL_MESSAGE =
            "Interrupted processing reached the configured attempt limit.";
    private static final String RECOVERY_LIMIT_USER_MESSAGE =
            "This processing attempt could not be resumed automatically.";
    private static final String STATE_CONFLICT = "PROCESSING_STATE_CONFLICT";
    private static final String STATE_CONFLICT_INTERNAL_MESSAGE =
            "Persisted Analysis and Analysis job states do not match.";
    private static final String STATE_CONFLICT_USER_MESSAGE =
            "Processing could not continue because the saved state was inconsistent.";

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DocumentRepository documentRepository;
    private final Clock clock;
    private final int maxAttemptsPerStage;

    public RecoverProcessingJobTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            DocumentRepository documentRepository,
            Clock clock,
            ProcessingPolicy policy) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.clock = Objects.requireNonNull(clock);
        this.maxAttemptsPerStage = Objects.requireNonNull(policy).maxAttemptsPerStage();
    }

    @Transactional
    public void recover(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Instant now = clock.instant();

        Analysis analysis = analysisRepository.findByIdForProcessingForUpdate(analysisId).orElse(null);
        if (analysis == null) {
            return;
        }
        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId).orElse(null);
        if (job == null) {
            return;
        }

        if (job.status() == AnalysisJobStatus.RUNNING) {
            recoverInterruptedRun(analysis, job, now);
        } else if (job.status() == AnalysisJobStatus.WAITING_FOR_USER) {
            recoverLegacyWaitingStage(analysis, job, now);
        }
    }

    private void recoverInterruptedRun(Analysis analysis, AnalysisJob job, Instant now) {
        AnalysisStatus stage = job.currentStage();
        if (!isMachineStage(stage)) {
            return;
        }
        if (analysis.status() != stage) {
            failStateConflict(analysis, job, stage, now);
            return;
        }
        if (isGuestExpired(analysis, now)) {
            failRecovery(analysis, job, stage, ANALYSIS_EXPIRED,
                    EXPIRED_INTERNAL_MESSAGE, EXPIRED_USER_MESSAGE, now);
            return;
        }

        AnalysisJob recoveredJob = job.recoverInterruptedAttempt(maxAttemptsPerStage, now);
        if (recoveredJob.status() == AnalysisJobStatus.FAILED) {
            Analysis failedAnalysis = analysis.failProcessing(
                    stage, RECOVERY_LIMIT_EXCEEDED, RECOVERY_LIMIT_USER_MESSAGE, false, now);
            analysisRepository.update(failedAnalysis);
        }
        analysisJobRepository.update(recoveredJob);
    }

    private void recoverLegacyWaitingStage(Analysis analysis, AnalysisJob job, Instant now) {
        AnalysisStatus stage = job.currentStage();
        if (!isMachineStage(stage)) {
            return;
        }
        if (analysis.status() != stage) {
            failStateConflict(analysis, job, stage, now);
            return;
        }
        if (stage == AnalysisStatus.CLASSIFYING && bothDocumentsHaveDetectedTypes(analysis.id())) {
            return;
        }
        if (isGuestExpired(analysis, now)) {
            failRecovery(analysis, job, stage, ANALYSIS_EXPIRED,
                    EXPIRED_INTERNAL_MESSAGE, EXPIRED_USER_MESSAGE, now);
            return;
        }

        analysisJobRepository.update(job.queueLegacyMachineStage(now));
    }

    private boolean bothDocumentsHaveDetectedTypes(UUID analysisId) {
        boolean referenceDetected = false;
        boolean invoiceDetected = false;
        List<Document> documents = documentRepository.findByAnalysisId(analysisId);
        for (Document document : documents) {
            if (document.detectedType() == null) {
                continue;
            }
            if (document.role() == DocumentRole.REFERENCE) {
                referenceDetected = true;
            } else if (document.role() == DocumentRole.INVOICE) {
                invoiceDetected = true;
            }
        }
        return referenceDetected && invoiceDetected;
    }

    private void failStateConflict(
            Analysis analysis,
            AnalysisJob job,
            AnalysisStatus failedStage,
            Instant now) {
        Analysis failedAnalysis = analysis.failProcessingStateConflict(
                STATE_CONFLICT, STATE_CONFLICT_USER_MESSAGE, now);
        failRecovery(analysis, job, failedStage, STATE_CONFLICT,
                STATE_CONFLICT_INTERNAL_MESSAGE, now, failedAnalysis);
    }

    private void failRecovery(
            Analysis analysis,
            AnalysisJob job,
            AnalysisStatus failedStage,
            String code,
            String internalMessage,
            String userMessage,
            Instant now) {
        Analysis failedAnalysis = analysis.failProcessing(failedStage, code, userMessage, false, now);
        failRecovery(analysis, job, failedStage, code, internalMessage, now, failedAnalysis);
    }

    private void failRecovery(
            Analysis analysis,
            AnalysisJob job,
            AnalysisStatus failedStage,
            String code,
            String internalMessage,
            Instant now,
            Analysis failedAnalysis) {
        AnalysisJob failedJob = job.failRecovery(failedStage, code, internalMessage, now);
        analysisRepository.update(failedAnalysis);
        analysisJobRepository.update(failedJob);
    }

    private static boolean isGuestExpired(Analysis analysis, Instant now) {
        return analysis.owner() instanceof GuestSessionOwner guestOwner
                && !guestOwner.expiresAt().isAfter(now);
    }

    private static boolean isMachineStage(AnalysisStatus stage) {
        return stage == AnalysisStatus.CLASSIFYING
                || stage == AnalysisStatus.EXTRACTING
                || stage == AnalysisStatus.MATCHING
                || stage == AnalysisStatus.RECONCILING
                || stage == AnalysisStatus.GENERATING_REPORT;
    }
}
