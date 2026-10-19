package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestExtractionServiceTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final RegisteredUserOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final ExtractionRequestTypes REQUEST_TYPES = new ExtractionRequestTypes(
            DocumentType.PURCHASE_ORDER, DocumentType.INVOICE);

    @Test
    void publishesWakeSignalOnlyAfterTheQueueTransactionSucceeds() {
        QueueExtractionTransaction transaction = mock(QueueExtractionTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        AnalysisStatusSnapshot queued = snapshot(AnalysisStatus.EXTRACTING, AnalysisJobStatus.QUEUED,
                AnalysisStatus.EXTRACTING);
        when(transaction.queue(ANALYSIS_ID, OWNER, REQUEST_TYPES)).thenReturn(queued);
        RequestExtractionService service = new RequestExtractionService(transaction, signalPublisher);

        assertEquals(queued, service.request(ANALYSIS_ID, OWNER, REQUEST_TYPES));

        InOrder inOrder = inOrder(transaction, signalPublisher);
        inOrder.verify(transaction).queue(ANALYSIS_ID, OWNER, REQUEST_TYPES);
        inOrder.verify(signalPublisher).request(ANALYSIS_ID);
    }

    @Test
    void failedQueueTransactionDoesNotDispatch() {
        QueueExtractionTransaction transaction = mock(QueueExtractionTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        when(transaction.queue(ANALYSIS_ID, OWNER, REQUEST_TYPES))
                .thenThrow(new ExtractionConflictException());
        RequestExtractionService service = new RequestExtractionService(transaction, signalPublisher);

        assertThrows(ExtractionConflictException.class,
                () -> service.request(ANALYSIS_ID, OWNER, REQUEST_TYPES));

        verifyNoInteractions(signalPublisher);
    }

    @Test
    void doesNotDispatchWhenWorkerHasAlreadyClaimedTheQueuedStage() {
        QueueExtractionTransaction transaction = mock(QueueExtractionTransaction.class);
        AnalysisProcessingSignalPublisher signalPublisher = mock(AnalysisProcessingSignalPublisher.class);
        AnalysisStatusSnapshot running = snapshot(AnalysisStatus.EXTRACTING, AnalysisJobStatus.RUNNING,
                AnalysisStatus.EXTRACTING);
        when(transaction.queue(ANALYSIS_ID, OWNER, REQUEST_TYPES)).thenReturn(running);
        RequestExtractionService service = new RequestExtractionService(transaction, signalPublisher);

        assertEquals(running, service.request(ANALYSIS_ID, OWNER, REQUEST_TYPES));

        verifyNoInteractions(signalPublisher);
    }

    private static AnalysisStatusSnapshot snapshot(
            AnalysisStatus status,
            AnalysisJobStatus jobStatus,
            AnalysisStatus currentStage) {
        return new AnalysisStatusSnapshot(
                ANALYSIS_ID, status, jobStatus, currentStage,
                false, null, null, Instant.parse("2026-10-04T12:00:00Z"));
    }
}
