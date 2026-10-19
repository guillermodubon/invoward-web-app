package io.github.guillermodubon.invoward.processing.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProcessingConfiguration.class);

    @Test
    void bindsApprovedDefaultsAndCreatesOneNamedBoundedExecutor() throws Exception {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            ProcessingProperties properties = context.getBean(ProcessingProperties.class);
            assertThat(properties.executor().corePoolSize()).isEqualTo(2);
            assertThat(properties.executor().maxPoolSize()).isEqualTo(4);
            assertThat(properties.executor().queueCapacity()).isEqualTo(32);
            assertThat(properties.recoveryBatchSize()).isEqualTo(100);
            assertThat(properties.maxAttemptsPerStage()).isEqualTo(3);
            assertThat(properties.shutdownAwait()).isEqualTo(Duration.ofSeconds(30));

            assertThat(context.getBeansOfType(ThreadPoolTaskExecutor.class)).hasSize(1);
            ThreadPoolTaskExecutor executor = context.getBean(
                    ProcessingConfiguration.PROCESSING_TASK_EXECUTOR, ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getQueueCapacity()).isEqualTo(32);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("invoward-processing-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        });
    }

    @Test
    void bindsAllApprovedLowerAndUpperBoundaries() {
        contextRunner.withPropertyValues(
                "invoward.processing.executor.core-pool-size=1",
                "invoward.processing.executor.max-pool-size=1",
                "invoward.processing.executor.queue-capacity=1",
                "invoward.processing.recovery-batch-size=1",
                "invoward.processing.max-attempts-per-stage=1",
                "invoward.processing.shutdown-await=1s")
                .run(context -> assertThat(context).hasNotFailed());

        contextRunner.withPropertyValues(
                "invoward.processing.executor.core-pool-size=8",
                "invoward.processing.executor.max-pool-size=16",
                "invoward.processing.executor.queue-capacity=500",
                "invoward.processing.recovery-batch-size=500",
                "invoward.processing.max-attempts-per-stage=5",
                "invoward.processing.shutdown-await=2m")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsValuesOutsideEveryApprovedRange() {
        List<String[]> invalidConfigurations = List.of(
                new String[]{"invoward.processing.executor.core-pool-size=0"},
                new String[]{"invoward.processing.executor.core-pool-size=9"},
                new String[]{"invoward.processing.executor.core-pool-size=2",
                        "invoward.processing.executor.max-pool-size=1"},
                new String[]{"invoward.processing.executor.max-pool-size=17"},
                new String[]{"invoward.processing.executor.queue-capacity=0"},
                new String[]{"invoward.processing.executor.queue-capacity=501"},
                new String[]{"invoward.processing.recovery-batch-size=0"},
                new String[]{"invoward.processing.recovery-batch-size=501"},
                new String[]{"invoward.processing.max-attempts-per-stage=0"},
                new String[]{"invoward.processing.max-attempts-per-stage=6"},
                new String[]{"invoward.processing.shutdown-await=999ms"},
                new String[]{"invoward.processing.shutdown-await=2m1s"});

        for (String[] invalidConfiguration : invalidConfigurations) {
            contextRunner.withPropertyValues(invalidConfiguration)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void rejectsTasksWhenTheBoundedPoolAndQueueAreFull() throws Exception {
        contextRunner.withPropertyValues(
                "invoward.processing.executor.core-pool-size=1",
                "invoward.processing.executor.max-pool-size=1",
                "invoward.processing.executor.queue-capacity=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor executor = context.getBean(
                            ProcessingConfiguration.PROCESSING_TASK_EXECUTOR, ThreadPoolTaskExecutor.class);
                    CountDownLatch taskStarted = new CountDownLatch(1);
                    CountDownLatch releaseTask = new CountDownLatch(1);
                    CountDownLatch tasksFinished = new CountDownLatch(2);
                    try {
                        executor.execute(() -> {
                            taskStarted.countDown();
                            await(releaseTask);
                            tasksFinished.countDown();
                        });
                        assertThat(awaitWithin(taskStarted, 2, TimeUnit.SECONDS)).isTrue();
                        executor.execute(tasksFinished::countDown);

                        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
                        assertThatThrownBy(() -> executor.execute(() -> { }))
                                .isInstanceOf(TaskRejectedException.class);
                    } finally {
                        releaseTask.countDown();
                    }
                    assertThat(awaitWithin(tasksFinished, 2, TimeUnit.SECONDS)).isTrue();
                });
    }

    @Test
    void usesDedicatedThreadPrefixWithoutPropagatingSecurityContext() throws Exception {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(
                    ProcessingConfiguration.PROCESSING_TASK_EXECUTOR, ThreadPoolTaskExecutor.class);
            Authentication authentication = new UsernamePasswordAuthenticationToken("request-user", "n/a");
            SecurityContextHolder.getContext().setAuthentication(authentication);
            CountDownLatch taskFinished = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            AtomicReference<Authentication> workerAuthentication = new AtomicReference<>();
            try {
                executor.execute(() -> {
                    threadName.set(Thread.currentThread().getName());
                    workerAuthentication.set(SecurityContextHolder.getContext().getAuthentication());
                    taskFinished.countDown();
                });
                assertThat(awaitWithin(taskFinished, 2, TimeUnit.SECONDS)).isTrue();
                assertThat(threadName.get()).startsWith("invoward-processing-");
                assertThat(workerAuthentication.get()).isNull();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    @Test
    void waitsForRunningTasksOnContextShutdown() throws Exception {
        contextRunner.withPropertyValues("invoward.processing.shutdown-await=2s").run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(
                    ProcessingConfiguration.PROCESSING_TASK_EXECUTOR, ThreadPoolTaskExecutor.class);
            CountDownLatch taskStarted = new CountDownLatch(1);
            CountDownLatch releaseTask = new CountDownLatch(1);
            CountDownLatch closeStarted = new CountDownLatch(1);
            CountDownLatch contextClosed = new CountDownLatch(1);
            executor.execute(() -> {
                taskStarted.countDown();
                await(releaseTask);
            });
            try {
                assertThat(awaitWithin(taskStarted, 2, TimeUnit.SECONDS)).isTrue();
                Thread closer = new Thread(() -> {
                    closeStarted.countDown();
                    context.close();
                    contextClosed.countDown();
                });
                closer.start();
                assertThat(awaitWithin(closeStarted, 2, TimeUnit.SECONDS)).isTrue();
                assertThat(contextClosed.await(100, TimeUnit.MILLISECONDS)).isFalse();
                releaseTask.countDown();
                assertThat(awaitWithin(contextClosed, 2, TimeUnit.SECONDS)).isTrue();
                closer.join(TimeUnit.SECONDS.toMillis(2));
                assertThat(closer.isAlive()).isFalse();
            } finally {
                releaseTask.countDown();
            }
        });
    }

    @Test
    void drainsQueuedTasksWhenAPausedExecutorContextCloses() {
        contextRunner.withPropertyValues(
                "invoward.processing.executor.core-pool-size=1",
                "invoward.processing.executor.max-pool-size=1",
                "invoward.processing.shutdown-await=1s").run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(
                    ProcessingConfiguration.PROCESSING_TASK_EXECUTOR, ThreadPoolTaskExecutor.class);
            CountDownLatch runningTaskStarted = new CountDownLatch(1);
            CountDownLatch releaseRunningTask = new CountDownLatch(1);
            CountDownLatch queuedTaskFinished = new CountDownLatch(1);
            executor.execute(() -> {
                runningTaskStarted.countDown();
                await(releaseRunningTask);
            });
            try {
                assertThat(awaitWithin(runningTaskStarted, 2, TimeUnit.SECONDS)).isTrue();
                executor.execute(queuedTaskFinished::countDown);
                executor.stop();
                releaseRunningTask.countDown();

                context.close();

                assertThat(awaitWithin(queuedTaskFinished, 2, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.getThreadPoolExecutor().isTerminated()).isTrue();
            } finally {
                releaseRunningTask.countDown();
                // Release a paused worker even when the regression fails, so the test never leaks it.
                executor.start();
                executor.shutdown();
            }
        });
    }

    @Test
    void boundsShutdownWaitByConfiguredTimeout() {
        contextRunner.withPropertyValues("invoward.processing.shutdown-await=1s").run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(
                    ProcessingConfiguration.PROCESSING_TASK_EXECUTOR, ThreadPoolTaskExecutor.class);
            CountDownLatch taskStarted = new CountDownLatch(1);
            CountDownLatch releaseTask = new CountDownLatch(1);
            CountDownLatch taskFinished = new CountDownLatch(1);
            executor.execute(() -> {
                taskStarted.countDown();
                await(releaseTask);
                taskFinished.countDown();
            });
            assertThat(awaitWithin(taskStarted, 2, TimeUnit.SECONDS)).isTrue();

            long shutdownStartedAt = System.nanoTime();
            try {
                context.close();
                long shutdownDurationMillis = TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - shutdownStartedAt);
                assertThat(shutdownDurationMillis).isBetween(700L, 4_000L);
            } finally {
                releaseTask.countDown();
            }
            assertThat(awaitWithin(taskFinished, 2, TimeUnit.SECONDS)).isTrue();
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitWithin(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test executor", exception);
        }
    }
}
