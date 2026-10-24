package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadDocumentTransactionTest {

    private static final Instant LOOKUP_TIME = Instant.parse("2026-10-24T12:00:00Z");
    private static final Instant PREVIOUS_UPLOAD_TIME = LOOKUP_TIME.plusSeconds(1);
    private static final Instant AFTER_LOCKS_TIME = LOOKUP_TIME.plusSeconds(2);

    @Mock private AnalysisRepository analysisRepository;
    @Mock private AnalysisJobRepository analysisJobRepository;
    @Mock private AnalysisProcessingSignalPublisher signalPublisher;
    @Mock private DocumentRepository documentRepository;
    @Mock private Clock clock;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesFreshTransitionTimeAfterAcquiringBothLocks(boolean bothDocumentsPresent) {
        RegisteredUserOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        Analysis analysis = Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), LOOKUP_TIME.minusSeconds(10));
        AnalysisJob job = AnalysisJob.waitingForUser(
                UUID.randomUUID(), analysis.id(), analysis.createdAt());
        if (bothDocumentsPresent) {
            // A competing upload committed while this request was waiting for the parent lock.
            analysis = analysis.transitionToUploading(PREVIOUS_UPLOAD_TIME);
            job = job.awaitMoreUploads(PREVIOUS_UPLOAD_TIME);
        }
        Document incoming = document(analysis.id(), DocumentRole.INVOICE);
        when(clock.instant()).thenReturn(LOOKUP_TIME, AFTER_LOCKS_TIME);
        when(analysisRepository.findOwnedByIdForUpdate(analysis.id(), owner, LOOKUP_TIME))
                .thenReturn(Optional.of(analysis));
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id())).thenReturn(Optional.of(job));
        when(documentRepository.create(incoming)).thenReturn(incoming);
        when(documentRepository.findByAnalysisId(analysis.id())).thenReturn(bothDocumentsPresent
                ? List.of(document(analysis.id(), DocumentRole.REFERENCE), incoming)
                : List.of(incoming));

        UploadDocumentTransaction transaction = new UploadDocumentTransaction(
                analysisRepository, analysisJobRepository, signalPublisher, documentRepository, clock, 1_024);

        assertEquals(incoming, transaction.persist(owner, incoming));

        ArgumentCaptor<Analysis> updatedAnalysis = ArgumentCaptor.forClass(Analysis.class);
        ArgumentCaptor<AnalysisJob> updatedJob = ArgumentCaptor.forClass(AnalysisJob.class);
        verify(analysisRepository).update(updatedAnalysis.capture());
        verify(analysisJobRepository).update(updatedJob.capture());
        assertEquals(AFTER_LOCKS_TIME, updatedAnalysis.getValue().updatedAt());
        assertEquals(AFTER_LOCKS_TIME, updatedJob.getValue().updatedAt());
        assertEquals(bothDocumentsPresent ? AnalysisStatus.CLASSIFYING : AnalysisStatus.UPLOADING,
                updatedAnalysis.getValue().status());
        assertEquals(bothDocumentsPresent ? AnalysisJobStatus.QUEUED : AnalysisJobStatus.WAITING_FOR_USER,
                updatedJob.getValue().status());
        if (bothDocumentsPresent) {
            verify(signalPublisher).request(analysis.id());
        } else {
            verify(signalPublisher, never()).request(analysis.id());
        }
        var lockOrder = inOrder(clock, analysisRepository, analysisJobRepository);
        lockOrder.verify(clock).instant();
        lockOrder.verify(analysisRepository).findOwnedByIdForUpdate(analysis.id(), owner, LOOKUP_TIME);
        lockOrder.verify(analysisJobRepository).findJobByAnalysisIdForUpdate(analysis.id());
        lockOrder.verify(clock).instant();
    }

    private static Document document(UUID analysisId, DocumentRole role) {
        return Document.createUploaded(UUID.randomUUID(), analysisId, role, role + ".pdf", "application/pdf",
                100, 1, "a".repeat(64), "test/" + UUID.randomUUID(), null, LOOKUP_TIME);
    }
}
