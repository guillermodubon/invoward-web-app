package io.github.guillermodubon.invoward.processing.infrastructure;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.extraction.application.service.DetectDocumentTypesService;
import io.github.guillermodubon.invoward.processing.infrastructure.config.ProcessingConfiguration;
import io.github.guillermodubon.invoward.processing.infrastructure.config.ProcessingProperties;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "invoward.email.provider=disabled",
        "spring.ai.model.chat=none",
        "invoward.processing.executor.core-pool-size=2",
        "invoward.processing.executor.max-pool-size=2",
        "invoward.processing.executor.queue-capacity=1"
})
class ProcessingWorkerConcurrencyIT {

    @Autowired
    private ProcessingWorker processingWorker;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessingProperties processingProperties;

    @Autowired
    @Qualifier(ProcessingConfiguration.PROCESSING_TASK_EXECUTOR)
    private ThreadPoolTaskExecutor processingTaskExecutor;

    @MockitoBean
    private DetectDocumentTypesService detectDocumentTypesService;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void twoConcurrentWorkerTasksProduceOnlyOneDatabaseClaim() throws Exception {
        Fixture fixture = createQueuedClassification();
        when(detectDocumentTypesService.detect(fixture.analysisId(), fixture.owner())).thenReturn(List.of());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            List<Future<?>> executions = List.of(
                    workers.submit(() -> processAfterBarrier(fixture.analysisId(), ready, start)),
                    workers.submit(() -> processAfterBarrier(fixture.analysisId(), ready, start)));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> execution : executions) {
                execution.get(10, TimeUnit.SECONDS);
            }
        }

        AnalysisJob completedAttempt = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertThat(completedAttempt.attemptCount()).isEqualTo(1);
        assertThat(completedAttempt.status()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(completedAttempt.currentStage()).isEqualTo(AnalysisStatus.CLASSIFYING);
        verify(detectDocumentTypesService, times(1)).detect(fixture.analysisId(), fixture.owner());
    }

    @Test
    void dispatcherSuppressesDuplicateAnalysisAndRunsDifferentAnalysesConcurrently() throws Exception {
        Fixture first = createQueuedClassification();
        Fixture second = createQueuedClassification();
        CountDownLatch bothStagesEntered = new CountDownLatch(2);
        CountDownLatch releaseStages = new CountDownLatch(1);
        CountDownLatch bothStagesFinished = new CountDownLatch(2);
        AtomicInteger activeStages = new AtomicInteger();
        AtomicInteger maximumConcurrentStages = new AtomicInteger();
        Map<UUID, AtomicInteger> activePerAnalysis = new ConcurrentHashMap<>();
        Map<UUID, AtomicInteger> maximumPerAnalysis = new ConcurrentHashMap<>();

        when(detectDocumentTypesService.detect(any(UUID.class), any()))
                .thenAnswer(invocation -> {
                    UUID analysisId = invocation.getArgument(0);
                    int analysisActive = activePerAnalysis.computeIfAbsent(analysisId, ignored -> new AtomicInteger())
                            .incrementAndGet();
                    maximumPerAnalysis.computeIfAbsent(analysisId, ignored -> new AtomicInteger())
                            .accumulateAndGet(analysisActive, Math::max);
                    int totalActive = activeStages.incrementAndGet();
                    maximumConcurrentStages.accumulateAndGet(totalActive, Math::max);
                    bothStagesEntered.countDown();
                    try {
                        await(releaseStages);
                        return List.of();
                    } finally {
                        activeStages.decrementAndGet();
                        activePerAnalysis.get(analysisId).decrementAndGet();
                        bothStagesFinished.countDown();
                    }
                });

        ProcessingDispatcher dispatcher = isolatedDispatcher(List.of());
        assertThat(dispatcher.dispatch(first.analysisId())).isTrue();
        assertThat(dispatcher.dispatch(first.analysisId())).isFalse();
        assertThat(dispatcher.dispatch(second.analysisId())).isTrue();

        try {
            assertThat(bothStagesEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.dispatch(first.analysisId())).isFalse();
            assertThat(maximumConcurrentStages.get()).isEqualTo(2);
            assertThat(maximumPerAnalysis.get(first.analysisId()).get()).isEqualTo(1);
            assertThat(maximumPerAnalysis.get(second.analysisId()).get()).isEqualTo(1);
        } finally {
            releaseStages.countDown();
        }

        assertThat(bothStagesFinished.await(5, TimeUnit.SECONDS)).isTrue();
        assertClaimedOnce(first);
        assertClaimedOnce(second);
        verify(detectDocumentTypesService, times(1)).detect(first.analysisId(), first.owner());
        verify(detectDocumentTypesService, times(1)).detect(second.analysisId(), second.owner());
    }

    @Test
    void fullExecutorRejectsWithoutClaimAndPendingDrainProgressesAfterCapacityReturns() throws Exception {
        Fixture fixture = createQueuedClassification();
        CountDownLatch twoWorkersStarted = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch allBlockersFinished = new CountDownLatch(3);
        CountDownLatch classificationEntered = new CountDownLatch(1);
        ThreadPoolTaskExecutor boundedExecutor = new ProcessingConfiguration()
                .processingTaskExecutor(processingProperties);
        boundedExecutor.initialize();
        AnalysisJobRepository pendingJobs = mock(AnalysisJobRepository.class);
        when(pendingJobs.findAnalysisIdsByStatus(
                AnalysisJobStatus.QUEUED, processingProperties.recoveryBatchSize()))
                .thenReturn(List.of(fixture.analysisId()), List.of(fixture.analysisId()), List.of());
        ProcessingDispatcher dispatcher = new ProcessingDispatcher(
                boundedExecutor, processingWorker, pendingJobs, processingProperties);
        when(detectDocumentTypesService.detect(fixture.analysisId(), fixture.owner()))
                .thenAnswer(invocation -> {
                    classificationEntered.countDown();
                    return List.of();
                });

        Runnable blocker = () -> {
            twoWorkersStarted.countDown();
            try {
                await(releaseWorkers);
            } finally {
                allBlockersFinished.countDown();
            }
        };

        try {
            try {
                boundedExecutor.execute(blocker);
                boundedExecutor.execute(blocker);
                assertThat(twoWorkersStarted.await(5, TimeUnit.SECONDS)).isTrue();
                boundedExecutor.execute(blocker);

                assertThat(boundedExecutor.getThreadPoolExecutor().getActiveCount()).isEqualTo(2);
                assertThat(boundedExecutor.getThreadPoolExecutor().getQueue()).hasSize(1);
                assertThat(dispatcher.dispatchPending()).isZero();

                AnalysisJob stillQueued = analysisJobRepository.findByAnalysisId(fixture.analysisId())
                        .orElseThrow();
                assertThat(stillQueued.status()).isEqualTo(AnalysisJobStatus.QUEUED);
                assertThat(stillQueued.attemptCount()).isZero();
                verify(detectDocumentTypesService, never()).detect(fixture.analysisId(), fixture.owner());
            } finally {
                releaseWorkers.countDown();
            }

            assertThat(allBlockersFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.dispatchPending()).isEqualTo(1);
            assertThat(classificationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertClaimedOnce(fixture);
            verify(pendingJobs, times(3)).findAnalysisIdsByStatus(
                    AnalysisJobStatus.QUEUED, processingProperties.recoveryBatchSize());
        } finally {
            releaseWorkers.countDown();
            boundedExecutor.destroy();
        }
    }

    private ProcessingDispatcher isolatedDispatcher(List<UUID> queuedIds) {
        AnalysisJobRepository pendingJobs = mock(AnalysisJobRepository.class);
        when(pendingJobs.findAnalysisIdsByStatus(
                AnalysisJobStatus.QUEUED, processingProperties.recoveryBatchSize())).thenReturn(queuedIds);
        return new ProcessingDispatcher(processingTaskExecutor, processingWorker, pendingJobs, processingProperties);
    }

    private void assertClaimedOnce(Fixture fixture) {
        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertThat(job.status()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(job.currentStage()).isEqualTo(AnalysisStatus.CLASSIFYING);
        assertThat(job.attemptCount()).isEqualTo(1);
    }

    private void processAfterBarrier(UUID analysisId, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent worker start timed out");
            }
            processingWorker.process(analysisId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Worker test was interrupted", interrupted);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Controlled processing task timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Controlled processing task was interrupted", interrupted);
        }
    }

    private Fixture createQueuedClassification() {
        Instant createdAt = Instant.now().minus(20, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MICROS);
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), createdAt));
        analysis = analysisRepository.update(analysis.transitionToUploading(createdAt.plusSeconds(1)));
        analysisRepository.update(analysis.beginClassification(createdAt.plusSeconds(2)));

        AnalysisJob job = AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, createdAt)
                .awaitMoreUploads(createdAt.plusSeconds(1))
                .queueClassification(createdAt.plusSeconds(2));
        analysisJobRepository.create(job);
        return new Fixture(analysisId, owner);
    }

    private record Fixture(UUID analysisId, RegisteredUserOwner owner) {
    }
}
