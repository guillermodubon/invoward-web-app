package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.processing.application.exception.ProcessingRetryNotAllowedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetryAnalysisProcessingServiceTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("6e0ba434-f7ce-41c8-bd3e-1cc80fd55b86");
    private static final RegisteredUserOwner OWNER = new RegisteredUserOwner(
            UUID.fromString("14cfd71a-86db-485a-af8d-39b1ec90eb8a"));

    @Test
    void publishesWakeSignalOnlyAfterRetryTransactionReturnsSuccessfully() {
        RetryAnalysisProcessingTransaction transaction = mock(RetryAnalysisProcessingTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        AnalysisStatusSnapshot snapshot = new AnalysisStatusSnapshot(
                ANALYSIS_ID, AnalysisStatus.CLASSIFYING, AnalysisJobStatus.QUEUED,
                AnalysisStatus.CLASSIFYING, false, null, null, Instant.parse("2026-10-01T00:00:00Z"));
        when(transaction.retry(ANALYSIS_ID, OWNER)).thenReturn(snapshot);
        RetryAnalysisProcessingService service = new RetryAnalysisProcessingService(transaction, signalPublisher);

        AnalysisStatusSnapshot result = service.retry(ANALYSIS_ID, OWNER);

        assertEquals(snapshot, result);
        verify(signalPublisher).request(ANALYSIS_ID);
    }

    @Test
    void doesNotPublishWhenRetryTransactionRejectsTheCurrentState() {
        RetryAnalysisProcessingTransaction transaction = mock(RetryAnalysisProcessingTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        when(transaction.retry(ANALYSIS_ID, OWNER)).thenThrow(new ProcessingRetryNotAllowedException());
        RetryAnalysisProcessingService service = new RetryAnalysisProcessingService(transaction, signalPublisher);

        assertThrows(ProcessingRetryNotAllowedException.class, () -> service.retry(ANALYSIS_ID, OWNER));

        verifyNoInteractions(signalPublisher);
    }
}
