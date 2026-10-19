package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestAnalysisProcessingServiceTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());

    @Test
    void queuedReconciliationIsSignalledAfterTheQueueTransactionAndRepeatedRequestsAreIdempotent() {
        RequestAnalysisProcessingTransaction transaction = mock(RequestAnalysisProcessingTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        AnalysisStatusSnapshot queued = snapshot(AnalysisJobStatus.QUEUED, AnalysisStatus.RECONCILING);
        when(transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING)).thenReturn(queued);
        RequestAnalysisProcessingService service = new RequestAnalysisProcessingService(transaction, signalPublisher);

        assertEquals(queued, service.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING));
        assertEquals(queued, service.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING));

        var order = inOrder(transaction, signalPublisher);
        order.verify(transaction).request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING);
        order.verify(signalPublisher).request(ANALYSIS_ID);
        order.verify(transaction).request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING);
        order.verify(signalPublisher).request(ANALYSIS_ID);
    }

    @Test
    void runningOrAlreadyAdvancedStageDoesNotSignalAgain() {
        RequestAnalysisProcessingTransaction transaction = mock(RequestAnalysisProcessingTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        when(transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING))
                .thenReturn(snapshot(AnalysisJobStatus.RUNNING, AnalysisStatus.RECONCILING))
                .thenReturn(snapshot(AnalysisJobStatus.QUEUED, AnalysisStatus.GENERATING_REPORT));
        RequestAnalysisProcessingService service = new RequestAnalysisProcessingService(transaction, signalPublisher);

        assertEquals(AnalysisJobStatus.RUNNING,
                service.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING).jobStatus());
        assertEquals(AnalysisStatus.GENERATING_REPORT,
                service.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING).status());

        verifyNoInteractions(signalPublisher);
    }

    @Test
    void failedRequestDoesNotSignalTheWorker() {
        RequestAnalysisProcessingTransaction transaction = mock(RequestAnalysisProcessingTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        when(transaction.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING))
                .thenThrow(new IllegalStateException("not allowed"));
        RequestAnalysisProcessingService service = new RequestAnalysisProcessingService(transaction, signalPublisher);

        assertThrows(IllegalStateException.class,
                () -> service.request(ANALYSIS_ID, OWNER, AnalysisStatus.RECONCILING));

        verifyNoInteractions(signalPublisher);
    }

    private static AnalysisStatusSnapshot snapshot(AnalysisJobStatus jobStatus, AnalysisStatus stage) {
        return new AnalysisStatusSnapshot(ANALYSIS_ID, stage, jobStatus, stage,
                false, null, null, Instant.parse("2026-10-17T12:00:00Z"));
    }
}
