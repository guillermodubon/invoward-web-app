package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingPolicy;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingWakePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Performs startup recovery in bounded ID pages, with one short transaction per recoverable job. */
@Service
public final class RecoverProcessingJobsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecoverProcessingJobsService.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final RecoverProcessingJobTransaction recoveryTransaction;
    private final ProcessingWakePort wakePort;
    private final int recoveryBatchSize;

    public RecoverProcessingJobsService(
            AnalysisJobRepository analysisJobRepository,
            RecoverProcessingJobTransaction recoveryTransaction,
            ProcessingWakePort wakePort,
            ProcessingPolicy policy) {
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.recoveryTransaction = Objects.requireNonNull(recoveryTransaction);
        this.wakePort = Objects.requireNonNull(wakePort);
        this.recoveryBatchSize = Objects.requireNonNull(policy).recoveryBatchSize();
    }

    /** Recovers interrupted and legacy waiting work, then wakes one bounded page of queued jobs. */
    public void recoverAtStartup() {
        recoverStatusInPages(AnalysisJobStatus.RUNNING);
        recoverStatusInPages(AnalysisJobStatus.WAITING_FOR_USER);
        dispatchQueuedPage();
    }

    private void recoverStatusInPages(AnalysisJobStatus status) {
        UUID afterAnalysisId = null;
        List<UUID> page;
        do {
            page = analysisJobRepository.findAnalysisIdsByStatusAfter(
                    status, afterAnalysisId, recoveryBatchSize);
            for (UUID analysisId : page) {
                try {
                    recoveryTransaction.recover(analysisId);
                } catch (RuntimeException recoveryFailure) {
                    // Continue through this startup page; persisted state remains available for next restart.
                    LOGGER.warn("operation=processing_recovery result=failure");
                }
            }
            if (!page.isEmpty()) {
                afterAnalysisId = page.get(page.size() - 1);
            }
        } while (page.size() == recoveryBatchSize);
    }

    private void dispatchQueuedPage() {
        for (UUID analysisId : analysisJobRepository.findAnalysisIdsByStatus(
                AnalysisJobStatus.QUEUED, recoveryBatchSize)) {
            try {
                wakePort.wake(analysisId);
            } catch (RuntimeException wakeFailure) {
                // Queued rows are durable; a later worker drain or restart can wake them again.
                LOGGER.warn("operation=processing_startup_wake result=failure");
            }
        }
    }
}
