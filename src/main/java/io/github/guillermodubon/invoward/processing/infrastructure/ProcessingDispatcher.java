package io.github.guillermodubon.invoward.processing.infrastructure;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingWakePort;
import io.github.guillermodubon.invoward.processing.infrastructure.config.ProcessingConfiguration;
import io.github.guillermodubon.invoward.processing.infrastructure.config.ProcessingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Best-effort bounded dispatcher; PostgreSQL remains the durable source of queued work. */
@Component
public final class ProcessingDispatcher implements ProcessingWakePort {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingDispatcher.class);

    private final TaskExecutor taskExecutor;
    private final ProcessingWorker worker;
    private final AnalysisJobRepository analysisJobRepository;
    private final int recoveryBatchSize;
    private final Set<UUID> submittedAnalysisIds = ConcurrentHashMap.newKeySet();

    public ProcessingDispatcher(
            @Qualifier(ProcessingConfiguration.PROCESSING_TASK_EXECUTOR) TaskExecutor taskExecutor,
            ProcessingWorker worker,
            AnalysisJobRepository analysisJobRepository,
            ProcessingProperties properties) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.analysisJobRepository = Objects.requireNonNull(
                analysisJobRepository, "analysisJobRepository must not be null");
        this.recoveryBatchSize = Objects.requireNonNull(properties, "properties must not be null")
                .recoveryBatchSize();
    }

    @Override
    public void wake(UUID analysisId) {
        dispatch(analysisId);
    }

    /** Submits one Analysis at most once locally while its task is queued or running. */
    public boolean dispatch(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        if (!submittedAnalysisIds.add(analysisId)) {
            return false;
        }

        try {
            taskExecutor.execute(() -> runAndDrain(analysisId));
            return true;
        } catch (TaskRejectedException rejected) {
            // Backpressure is not a processing attempt: the persisted job remains QUEUED.
            submittedAnalysisIds.remove(analysisId);
            return false;
        } catch (RuntimeException executorFailure) {
            submittedAnalysisIds.remove(analysisId);
            throw executorFailure;
        }
    }

    /** Submits one bounded oldest-first page of durable QUEUED jobs. */
    public int dispatchPending() {
        int submitted = 0;
        for (UUID analysisId : analysisJobRepository.findAnalysisIdsByStatus(
                AnalysisJobStatus.QUEUED, recoveryBatchSize)) {
            if (dispatch(analysisId)) {
                submitted++;
            }
        }
        return submitted;
    }

    private void runAndDrain(UUID analysisId) {
        try {
            worker.process(analysisId);
        } catch (RuntimeException workerFailure) {
            // Keep exception text, IDs and stage input out of logs; later recovery owns stale work.
            LOGGER.warn("operation=processing_worker result=failure");
        } finally {
            submittedAnalysisIds.remove(analysisId);
            try {
                dispatchPending();
            } catch (RuntimeException drainFailure) {
                LOGGER.warn("operation=processing_queue_drain result=failure");
            }
        }
    }
}
