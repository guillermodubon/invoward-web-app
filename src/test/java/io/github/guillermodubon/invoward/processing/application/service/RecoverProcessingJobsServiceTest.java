package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingPolicy;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingWakePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RecoverProcessingJobsServiceTest {

    private static final int BATCH_SIZE = 2;

    @Test
    void recoversRunningAndLegacyWaitingPagesThenDispatchesOneQueuedPage() {
        AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
        RecoverProcessingJobTransaction transaction = mock(RecoverProcessingJobTransaction.class);
        ProcessingWakePort wakePort = mock(ProcessingWakePort.class);
        UUID runningA = UUID.randomUUID();
        UUID runningB = UUID.randomUUID();
        UUID runningC = UUID.randomUUID();
        UUID waitingA = UUID.randomUUID();
        UUID queuedA = UUID.randomUUID();
        UUID queuedB = UUID.randomUUID();

        when(jobs.findAnalysisIdsByStatusAfter(AnalysisJobStatus.RUNNING, null, BATCH_SIZE))
                .thenReturn(List.of(runningA, runningB));
        when(jobs.findAnalysisIdsByStatusAfter(AnalysisJobStatus.RUNNING, runningB, BATCH_SIZE))
                .thenReturn(List.of(runningC));
        when(jobs.findAnalysisIdsByStatusAfter(AnalysisJobStatus.WAITING_FOR_USER, null, BATCH_SIZE))
                .thenReturn(List.of(waitingA));
        when(jobs.findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, BATCH_SIZE))
                .thenReturn(List.of(queuedA, queuedB));

        service(jobs, transaction, wakePort).recoverAtStartup();

        verify(transaction).recover(runningA);
        verify(transaction).recover(runningB);
        verify(transaction).recover(runningC);
        verify(transaction).recover(waitingA);
        verify(wakePort).wake(queuedA);
        verify(wakePort).wake(queuedB);
        verify(jobs).findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, BATCH_SIZE);
        verify(jobs, never()).findAnalysisIdsByStatusAfter(AnalysisJobStatus.QUEUED, null, BATCH_SIZE);
        verify(jobs, never()).findAnalysisIdsByStatusAfter(AnalysisJobStatus.FAILED, null, BATCH_SIZE);
        verifyNoMoreInteractions(wakePort);
    }

    @Test
    void recoveryFailureForOneAnalysisDoesNotPreventOtherIdsFromBeingVisited() {
        AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
        RecoverProcessingJobTransaction transaction = mock(RecoverProcessingJobTransaction.class);
        ProcessingWakePort wakePort = mock(ProcessingWakePort.class);
        UUID failed = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(jobs.findAnalysisIdsByStatusAfter(AnalysisJobStatus.RUNNING, null, BATCH_SIZE))
                .thenReturn(List.of(failed, next));
        doThrow(new IllegalStateException("sensitive persistence detail")).when(transaction).recover(failed);
        when(jobs.findAnalysisIdsByStatusAfter(AnalysisJobStatus.WAITING_FOR_USER, null, BATCH_SIZE))
                .thenReturn(List.of());
        when(jobs.findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, BATCH_SIZE))
                .thenReturn(List.of());

        service(jobs, transaction, wakePort).recoverAtStartup();

        verify(transaction).recover(failed);
        verify(transaction).recover(next);
        verify(wakePort, never()).wake(failed);
    }

    private static RecoverProcessingJobsService service(
            AnalysisJobRepository jobs,
            RecoverProcessingJobTransaction transaction,
            ProcessingWakePort wakePort) {
        ProcessingPolicy policy = new ProcessingPolicy() {
            @Override
            public int recoveryBatchSize() {
                return BATCH_SIZE;
            }

            @Override
            public int maxAttemptsPerStage() {
                return 3;
            }
        };
        return new RecoverProcessingJobsService(jobs, transaction, wakePort, policy);
    }
}
