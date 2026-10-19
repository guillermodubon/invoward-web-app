package io.github.guillermodubon.invoward.processing.infrastructure.event;

import io.github.guillermodubon.invoward.processing.application.port.ProcessingWakePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProcessingSignalTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("d0d9a580-4550-4e6f-a0a1-6c2f8e8a4c70");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RecordingConfiguration.class);

    @Test
    void publisherEmitsOnlyTheInternalAnalysisWakeUpEvent() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        SpringAnalysisProcessingSignalPublisher publisher =
                new SpringAnalysisProcessingSignalPublisher(eventPublisher);

        publisher.request(ANALYSIS_ID);

        verify(eventPublisher).publishEvent(new ProcessingRequestedEvent(ANALYSIS_ID));
    }

    @Test
    void listenerWakesOnlyAfterTransactionCommit() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RecordingWakePort wakePort = context.getBean(RecordingWakePort.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            SpringAnalysisProcessingSignalPublisher publisher =
                    context.getBean(SpringAnalysisProcessingSignalPublisher.class);

            transaction.executeWithoutResult(status -> {
                publisher.request(ANALYSIS_ID);
                assertThat(wakePort.analysisIds()).isEmpty();
            });

            assertThat(wakePort.analysisIds()).containsExactly(ANALYSIS_ID);
        });
    }

    @Test
    void rollbackDoesNotWakeProcessing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RecordingWakePort wakePort = context.getBean(RecordingWakePort.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            SpringAnalysisProcessingSignalPublisher publisher =
                    context.getBean(SpringAnalysisProcessingSignalPublisher.class);

            transaction.executeWithoutResult(status -> {
                publisher.request(ANALYSIS_ID);
                status.setRollbackOnly();
            });

            assertThat(wakePort.analysisIds()).isEmpty();
        });
    }

    @Test
    void isolatedInvocationWithoutTransactionUsesSafeFallback() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RecordingWakePort wakePort = context.getBean(RecordingWakePort.class);

            context.getBean(SpringAnalysisProcessingSignalPublisher.class).request(ANALYSIS_ID);

            assertThat(wakePort.analysisIds()).containsExactly(ANALYSIS_ID);
        });
    }

    @Test
    void wakeFailureDoesNotUndoAnAlreadyCommittedTransaction() {
        new ApplicationContextRunner()
                .withUserConfiguration(FailingWakeConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RecordingTransactionManager transactionManager =
                            context.getBean(RecordingTransactionManager.class);
                    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

                    transaction.executeWithoutResult(status ->
                            context.getBean(SpringAnalysisProcessingSignalPublisher.class).request(ANALYSIS_ID));

                    assertThat(transactionManager.commits()).isEqualTo(1);
                    assertThat(transactionManager.rollbacks()).isZero();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({SpringAnalysisProcessingSignalPublisher.class, ProcessingRequestedEventListener.class})
    static class RecordingConfiguration {

        @Bean
        RecordingWakePort processingWakePort() {
            return new RecordingWakePort();
        }

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({SpringAnalysisProcessingSignalPublisher.class, ProcessingRequestedEventListener.class})
    static class FailingWakeConfiguration {

        @Bean
        ProcessingWakePort processingWakePort() {
            return analysisId -> {
                throw new IllegalStateException("wake failure");
            };
        }

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }

    static class RecordingWakePort implements ProcessingWakePort {

        private final List<UUID> analysisIds = new ArrayList<>();

        @Override
        public void wake(UUID analysisId) {
            analysisIds.add(analysisId);
        }

        List<UUID> analysisIds() {
            return List.copyOf(analysisIds);
        }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // Transaction synchronization is the behavior under test; no resource is needed.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }

        int commits() {
            return commits.get();
        }

        int rollbacks() {
            return rollbacks.get();
        }
    }
}
