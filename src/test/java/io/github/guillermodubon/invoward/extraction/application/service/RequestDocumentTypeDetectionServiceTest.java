package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
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

class RequestDocumentTypeDetectionServiceTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();

    @Test
    void queuedCommandWakesWorkerAfterQueueTransactionForRegisteredAndGuestOwners() {
        QueueDocumentTypeDetectionTransaction transaction = mock(QueueDocumentTypeDetectionTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        AnalysisOwner registered = new RegisteredUserOwner(UUID.randomUUID());
        AnalysisOwner guest = new GuestSessionOwner(UUID.randomUUID(), Instant.parse("2026-10-06T00:00:00Z"));
        AnalysisStatusSnapshot queued = snapshot(AnalysisJobStatus.QUEUED);
        when(transaction.queue(ANALYSIS_ID, registered)).thenReturn(queued);
        when(transaction.queue(ANALYSIS_ID, guest)).thenReturn(queued);
        RequestDocumentTypeDetectionService service =
                new RequestDocumentTypeDetectionService(transaction, signalPublisher);

        assertEquals(queued, service.request(ANALYSIS_ID, registered));
        assertEquals(queued, service.request(ANALYSIS_ID, guest));

        var order = inOrder(transaction, signalPublisher);
        order.verify(transaction).queue(ANALYSIS_ID, registered);
        order.verify(signalPublisher).request(ANALYSIS_ID);
        order.verify(transaction).queue(ANALYSIS_ID, guest);
        order.verify(signalPublisher).request(ANALYSIS_ID);
    }

    @Test
    void runningOrAlreadyDetectedCommandDoesNotWakeTheWorkerAgain() {
        QueueDocumentTypeDetectionTransaction transaction = mock(QueueDocumentTypeDetectionTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        when(transaction.queue(ANALYSIS_ID, owner)).thenReturn(
                snapshot(AnalysisJobStatus.RUNNING),
                new AnalysisStatusSnapshot(ANALYSIS_ID, AnalysisStatus.CLASSIFYING,
                        AnalysisJobStatus.WAITING_FOR_USER, AnalysisStatus.CLASSIFYING,
                        false, null, null, Instant.parse("2026-10-05T12:00:00Z")));
        RequestDocumentTypeDetectionService service =
                new RequestDocumentTypeDetectionService(transaction, signalPublisher);

        assertEquals(AnalysisJobStatus.RUNNING, service.request(ANALYSIS_ID, owner).jobStatus());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, service.request(ANALYSIS_ID, owner).jobStatus());

        verifyNoInteractions(signalPublisher);
    }

    @Test
    void failedQueueTransactionDoesNotWakeWorker() {
        QueueDocumentTypeDetectionTransaction transaction = mock(QueueDocumentTypeDetectionTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        when(transaction.queue(ANALYSIS_ID, owner)).thenThrow(new IllegalStateException("conflict"));
        RequestDocumentTypeDetectionService service =
                new RequestDocumentTypeDetectionService(transaction, signalPublisher);

        assertThrows(IllegalStateException.class, () -> service.request(ANALYSIS_ID, owner));

        verifyNoInteractions(signalPublisher);
    }

    private static AnalysisStatusSnapshot snapshot(AnalysisJobStatus jobStatus) {
        return new AnalysisStatusSnapshot(ANALYSIS_ID, AnalysisStatus.CLASSIFYING,
                jobStatus, AnalysisStatus.CLASSIFYING, false, null, null,
                Instant.parse("2026-10-05T12:00:00Z"));
    }
}
