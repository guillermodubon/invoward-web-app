package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueDocumentTypeDetectionTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final AnalysisOwner REGISTERED_OWNER = new RegisteredUserOwner(UUID.randomUUID());

    @Mock private AnalysisRepository analysisRepository;
    @Mock private AnalysisJobRepository analysisJobRepository;
    @Mock private DocumentRepository documentRepository;

    private QueueDocumentTypeDetectionTransaction transaction;
    private Analysis classifying;
    private AnalysisJob queuedJob;
    private AnalysisJob runningJob;
    private AnalysisJob waitingJob;
    private Document reference;
    private Document invoice;

    @BeforeEach
    void setUp() {
        transaction = new QueueDocumentTypeDetectionTransaction(
                analysisRepository,
                analysisJobRepository,
                documentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        classifying = Analysis.create(ANALYSIS_ID, REGISTERED_OWNER, PriceTolerance.exactMatch(), NOW.minusSeconds(20))
                .transitionToUploading(NOW.minusSeconds(19))
                .beginClassification(NOW.minusSeconds(18));
        queuedJob = AnalysisJob.waitingForUser(JOB_ID, ANALYSIS_ID, NOW.minusSeconds(20))
                .awaitMoreUploads(NOW.minusSeconds(19))
                .queueClassification(NOW.minusSeconds(18));
        runningJob = queuedJob.startQueuedAttempt(NOW.minusSeconds(17), 3);
        waitingJob = runningJob.finishClassificationAwaitingUser(NOW.minusSeconds(16));
        reference = document(DocumentRole.REFERENCE, null, null);
        invoice = document(DocumentRole.INVOICE, null, null);

        lenient().when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.of(classifying));
        lenient().when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID))
                .thenReturn(Optional.of(queuedJob));
        lenient().when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(invoice, reference));
        lenient().when(analysisJobRepository.update(any(AnalysisJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void queuedClassificationIsIdempotentlyReturnedForRegisteredOwner() {
        var snapshot = transaction.queue(ANALYSIS_ID, REGISTERED_OWNER);

        assertEquals(AnalysisJobStatus.QUEUED, snapshot.jobStatus());
        assertEquals(AnalysisStatus.CLASSIFYING, snapshot.currentStage());
        verify(analysisJobRepository, never()).update(any());
    }

    @Test
    void runningClassificationIsReturnedWithoutASecondTransition() {
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(runningJob));

        var snapshot = transaction.queue(ANALYSIS_ID, REGISTERED_OWNER);

        assertEquals(AnalysisJobStatus.RUNNING, snapshot.jobStatus());
        verify(analysisJobRepository, never()).update(any());
    }

    @Test
    void alreadyDetectedClassificationWaitingForUserReturnsCurrentState() {
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(waitingJob));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(
                document(DocumentRole.REFERENCE, DocumentType.QUOTE, null),
                document(DocumentRole.INVOICE, DocumentType.INVOICE, null)));

        var snapshot = transaction.queue(ANALYSIS_ID, REGISTERED_OWNER);

        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, snapshot.jobStatus());
        assertEquals(AnalysisStatus.CLASSIFYING, snapshot.currentStage());
        verify(analysisJobRepository, never()).update(any());
    }

    @Test
    void legacyClassificationWaitingWithoutAllDetectedTypesIsQueued() {
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(waitingJob));

        var snapshot = transaction.queue(ANALYSIS_ID, REGISTERED_OWNER);

        assertEquals(AnalysisJobStatus.QUEUED, snapshot.jobStatus());
        assertEquals(AnalysisStatus.CLASSIFYING, snapshot.currentStage());
        ArgumentCaptor<AnalysisJob> updated = ArgumentCaptor.forClass(AnalysisJob.class);
        verify(analysisJobRepository).update(updated.capture());
        assertEquals(AnalysisJobStatus.QUEUED, updated.getValue().status());
        assertEquals(AnalysisStatus.CLASSIFYING, updated.getValue().currentStage());
    }

    @Test
    void requestAfterClassificationPassedReturnsCurrentPersistedStageWithoutWrites() {
        Analysis extracting = withStatus(classifying, AnalysisStatus.EXTRACTING);
        AnalysisJob extractingJob = waitingJob.queueExtraction(NOW.minusSeconds(15));
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.of(extracting));
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID))
                .thenReturn(Optional.of(extractingJob));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(
                document(DocumentRole.REFERENCE, DocumentType.QUOTE, null),
                document(DocumentRole.INVOICE, DocumentType.INVOICE, null)));

        var snapshot = transaction.queue(ANALYSIS_ID, REGISTERED_OWNER);

        assertEquals(AnalysisStatus.EXTRACTING, snapshot.status());
        assertEquals(AnalysisJobStatus.QUEUED, snapshot.jobStatus());
        verify(analysisJobRepository, never()).update(any());
    }

    @Test
    void wrongOwnerIsRejectedBeforeJobOrDocumentAccess() {
        AnalysisOwner wrongOwner = new RegisteredUserOwner(UUID.randomUUID());
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, wrongOwner, NOW))
                .thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> transaction.queue(ANALYSIS_ID, wrongOwner));

        verifyNoInteractions(analysisJobRepository, documentRepository);
    }

    @Test
    void missingRequiredDocumentsProduceConflict() {
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference));

        assertThrows(DocumentsRequiredException.class, () -> transaction.queue(ANALYSIS_ID, REGISTERED_OWNER));

        verify(analysisJobRepository, never()).update(any());
    }

    @Test
    void expiredGuestDocumentsCannotBeQueuedOrReportedAsReady() {
        GuestSessionOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), NOW.minusSeconds(1));
        Analysis expiredGuestAnalysis = Analysis.create(
                        ANALYSIS_ID, guestOwner, PriceTolerance.exactMatch(), NOW.minusSeconds(30))
                .transitionToUploading(NOW.minusSeconds(20))
                .beginClassification(NOW.minusSeconds(10));
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, guestOwner, NOW))
                .thenReturn(Optional.of(expiredGuestAnalysis));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(
                document(DocumentRole.REFERENCE, null, guestOwner.expiresAt()),
                document(DocumentRole.INVOICE, null, guestOwner.expiresAt())));

        assertThrows(DocumentClassificationConflictException.class,
                () -> transaction.queue(ANALYSIS_ID, guestOwner));

        verify(analysisJobRepository, never()).update(any());
    }

    private static Document document(DocumentRole role, DocumentType detectedType, Instant expiresAt) {
        Document document = Document.createUploaded(
                UUID.randomUUID(), ANALYSIS_ID, role, role + ".pdf", "application/pdf",
                100, 1, "a".repeat(64), "private-key-" + role, expiresAt, NOW.minusSeconds(30));
        return detectedType == null ? document : document.withDetectedType(detectedType);
    }

    private static Analysis withStatus(Analysis source, AnalysisStatus status) {
        return new Analysis(source.id(), source.owner(), status, source.reviewStatus(), source.reviewedAt(), source.reconciliationStatus(),
                source.supplierName(), source.supplierKey(), source.referenceType(), source.referenceNumber(),
                source.invoiceNumber(), source.currency(), source.referenceTotal(), source.invoicedTotal(),
                source.difference(), source.priceTolerance(), source.retryable(), source.failureCode(),
                source.failureUserMessage(), source.version(), source.completedAt(), source.expiresAt(),
                source.createdAt(), source.updatedAt());
    }
}
