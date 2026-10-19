package io.github.guillermodubon.invoward.processing.infrastructure;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.processing.infrastructure.config.ProcessingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessingDispatcherTest {

    @Test
    void suppressesDuplicateWakeupsAndDrainsOneBoundedPageAfterWorkerCompletes() {
        QueuedTaskExecutor executor = new QueuedTaskExecutor();
        ProcessingWorker worker = mock(ProcessingWorker.class);
        AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
        UUID first = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(jobs.findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, 7)).thenReturn(List.of(next));
        ProcessingDispatcher dispatcher = dispatcher(executor, worker, jobs, 7);

        assertThat(dispatcher.dispatch(first)).isTrue();
        assertThat(dispatcher.dispatch(first)).isFalse();
        assertThat(executor.queuedTaskCount()).isEqualTo(1);

        executor.runNext();

        verify(worker).process(first);
        verify(jobs).findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, 7);
        assertThat(executor.queuedTaskCount()).isEqualTo(1);
        assertThat(dispatcher.dispatch(next)).isFalse();
        assertThat(dispatcher.dispatch(first)).isTrue();
    }

    @Test
    void executorRejectionLeavesJobAvailableForALaterBoundedDrain() {
        QueuedTaskExecutor executor = new QueuedTaskExecutor();
        executor.reject = true;
        ProcessingWorker worker = mock(ProcessingWorker.class);
        AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
        UUID analysisId = UUID.randomUUID();
        when(jobs.findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, 4)).thenReturn(List.of(analysisId));
        ProcessingDispatcher dispatcher = dispatcher(executor, worker, jobs, 4);

        assertThat(dispatcher.dispatch(analysisId)).isFalse();
        assertThat(dispatcher.dispatchPending()).isZero();
        verify(worker, never()).process(analysisId);

        executor.reject = false;
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        assertThat(executor.queuedTaskCount()).isEqualTo(1);
        assertThat(dispatcher.dispatch(analysisId)).isFalse();
    }

    @Test
    void clearsSubmittedIdEvenWhenWorkerEscapesWithUnexpectedFailure() {
        QueuedTaskExecutor executor = new QueuedTaskExecutor();
        ProcessingWorker worker = mock(ProcessingWorker.class);
        AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
        UUID analysisId = UUID.randomUUID();
        when(jobs.findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, 3)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("sensitive detail"))
                .when(worker).process(analysisId);
        ProcessingDispatcher dispatcher = dispatcher(executor, worker, jobs, 3);

        assertThat(dispatcher.dispatch(analysisId)).isTrue();
        executor.runNext();

        assertThat(dispatcher.dispatch(analysisId)).isTrue();
        verify(jobs).findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, 3);
    }

    @Test
    void boundedRealExecutorRejectionCanBeRetriedAfterCapacityReturns() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();

        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        CountDownLatch queuedTaskFinished = new CountDownLatch(1);
        ProcessingWorker worker = mock(ProcessingWorker.class);
        AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
        when(jobs.findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, 2)).thenReturn(List.of());
        ProcessingDispatcher dispatcher = dispatcher(executor, worker, jobs, 2);
        UUID analysisId = UUID.randomUUID();

        try {
            executor.execute(() -> {
                firstTaskStarted.countDown();
                await(releaseFirstTask);
            });
            assertThat(firstTaskStarted.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute(queuedTaskFinished::countDown);

            assertThat(dispatcher.dispatch(analysisId)).isFalse();
            verify(worker, never()).process(analysisId);

            releaseFirstTask.countDown();
            assertThat(queuedTaskFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.dispatch(analysisId)).isTrue();
            verify(worker, timeout(2_000)).process(analysisId);
        } finally {
            releaseFirstTask.countDown();
            executor.destroy();
        }
    }

    private static ProcessingDispatcher dispatcher(
            TaskExecutor executor, ProcessingWorker worker, AnalysisJobRepository jobs, int batchSize) {
        return new ProcessingDispatcher(executor, worker, jobs,
                new ProcessingProperties(
                        new ProcessingProperties.Executor(1, 1, 1), batchSize, 3, Duration.ofSeconds(30)));
    }

    private static final class QueuedTaskExecutor implements TaskExecutor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean reject;

        @Override
        public void execute(Runnable task) {
            if (reject) {
                throw new TaskRejectedException("bounded executor is full");
            }
            tasks.add(task);
        }

        private int queuedTaskCount() {
            return tasks.size();
        }

        private void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
