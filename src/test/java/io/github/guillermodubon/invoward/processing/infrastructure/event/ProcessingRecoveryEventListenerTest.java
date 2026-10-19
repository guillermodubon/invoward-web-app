package io.github.guillermodubon.invoward.processing.infrastructure.event;

import io.github.guillermodubon.invoward.processing.application.service.RecoverProcessingJobsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class ProcessingRecoveryEventListenerTest {

    @Test
    void invokesRecoveryWhenApplicationIsReady() {
        RecoverProcessingJobsService service = mock(RecoverProcessingJobsService.class);
        ProcessingRecoveryEventListener listener = new ProcessingRecoveryEventListener(service);

        listener.onApplicationReady(mock(ApplicationReadyEvent.class));

        verify(service).recoverAtStartup();
    }

    @Test
    void leavesStartupEventUsableWhenRecoveryFails() {
        RecoverProcessingJobsService service = mock(RecoverProcessingJobsService.class);
        doThrow(new IllegalStateException("database error detail")).when(service).recoverAtStartup();
        ProcessingRecoveryEventListener listener = new ProcessingRecoveryEventListener(service);

        listener.onApplicationReady(mock(ApplicationReadyEvent.class));

        verify(service).recoverAtStartup();
    }
}
