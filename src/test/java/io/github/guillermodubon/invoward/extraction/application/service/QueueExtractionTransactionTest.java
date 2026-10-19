package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.AnalysisExtractionNotAllowedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ConfirmedDocumentTypeInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentTypesNotDetectedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueExtractionTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:03Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final ExtractionRequestTypes REQUEST_TYPES = new ExtractionRequestTypes(
            DocumentType.PURCHASE_ORDER, DocumentType.INVOICE);

    @Mock private AnalysisRepository analysisRepository;
    @Mock private AnalysisJobRepository analysisJobRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private ExtractedDocumentRepository extractedDocumentRepository;

    private ConfirmExtractionTypesTransaction confirmTypesTransaction;
    private QueueExtractionTransaction transaction;
    private Analysis classifyingAnalysis;
    private AnalysisJob waitingJob;
    private Document reference;
    private Document invoice;

    @BeforeEach
    void setUp() {
        confirmTypesTransaction = new ConfirmExtractionTypesTransaction(
                analysisRepository, documentRepository, extractedDocumentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        transaction = new QueueExtractionTransaction(
                analysisRepository,
                analysisJobRepository,
                documentRepository,
                confirmTypesTransaction,
                Clock.fixed(NOW, ZoneOffset.UTC));

        classifyingAnalysis = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW.minusSeconds(3))
                .transitionToUploading(NOW.minusSeconds(2))
                .beginClassification(NOW.minusSeconds(1));
        waitingJob = new AnalysisJob(
                UUID.randomUUID(), ANALYSIS_ID, AnalysisJobStatus.WAITING_FOR_USER,
                AnalysisStatus.CLASSIFYING, 0, false, null, null,
                null, null, NOW.minusSeconds(3), NOW.minusSeconds(1));
        reference = document(DocumentRole.REFERENCE, DocumentType.INVOICE, null);
        invoice = document(DocumentRole.INVOICE, DocumentType.QUOTE, null);

        lenient().when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(classifyingAnalysis));
        lenient().when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID))
                .thenReturn(Optional.of(waitingJob));
        lenient().when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(invoice, reference));
        lenient().when(extractedDocumentRepository.findByDocumentId(reference.id())).thenReturn(Optional.empty());
        lenient().when(extractedDocumentRepository.findByDocumentId(invoice.id())).thenReturn(Optional.empty());
        lenient().when(documentRepository.updateTypes(any(Document.class)))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        lenient().when(analysisRepository.update(any(Analysis.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(analysisJobRepository.update(any(AnalysisJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void confirmsTypesAndQueuesAnalysisAndJobAtomically() {
        var result = transaction.queue(ANALYSIS_ID, OWNER, REQUEST_TYPES);

        assertEquals(AnalysisStatus.EXTRACTING, result.status());
        assertEquals(AnalysisJobStatus.QUEUED, result.jobStatus());
        assertEquals(AnalysisStatus.EXTRACTING, result.currentStage());
        verify(documentRepository).updateTypes(reference.withConfirmedType(DocumentType.PURCHASE_ORDER));
        verify(documentRepository).updateTypes(invoice.withConfirmedType(DocumentType.INVOICE));
        verify(analysisRepository).update(classifyingAnalysis.beginExtraction(NOW));
        verify(analysisJobRepository).update(waitingJob.queueExtraction(NOW));
    }

    @Test
    void rejectsAnOwnerMismatchBeforeReadingJobOrDocuments() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> transaction.queue(ANALYSIS_ID, OWNER, REQUEST_TYPES));

        verifyNoInteractions(analysisJobRepository, documentRepository, extractedDocumentRepository);
    }

    @Test
    void rejectsAnIncompatibleRoleTypeWithoutChangingTypesOrWorkflowState() {
        assertThrows(ConfirmedDocumentTypeInvalidException.class,
                () -> transaction.queue(ANALYSIS_ID, OWNER,
                        new ExtractionRequestTypes(DocumentType.INVOICE, DocumentType.INVOICE)));

        verify(documentRepository, never()).updateTypes(any(Document.class));
        verify(analysisRepository, never()).update(any(Analysis.class));
        verify(analysisJobRepository, never()).update(any(AnalysisJob.class));
    }

    @Test
    void requiresBothDetectedTypesBeforePersistingConfirmation() {
        reference = document(DocumentRole.REFERENCE, null, null);
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));

        assertThrows(DocumentTypesNotDetectedException.class,
                () -> transaction.queue(ANALYSIS_ID, OWNER, REQUEST_TYPES));

        verify(documentRepository, never()).updateTypes(any(Document.class));
        verify(analysisRepository, never()).update(any(Analysis.class));
        verify(analysisJobRepository, never()).update(any(AnalysisJob.class));
    }

    @Test
    void rejectsNonClassifyingAnalysisBeforeConfirmingTypes() {
        Analysis uploading = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW.minusSeconds(2))
                .transitionToUploading(NOW.minusSeconds(1));
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(uploading));

        assertThrows(AnalysisExtractionNotAllowedException.class,
                () -> transaction.queue(ANALYSIS_ID, OWNER, REQUEST_TYPES));

        verify(documentRepository, never()).updateTypes(any(Document.class));
        verify(analysisRepository, never()).update(any(Analysis.class));
        verify(analysisJobRepository, never()).update(any(AnalysisJob.class));
    }

    @Test
    void repeatedRequestForSameQueuedTypesIsIdempotent() {
        Analysis extracting = classifyingAnalysis.beginExtraction(NOW.minusMillis(1));
        AnalysisJob queued = waitingJob.queueExtraction(NOW.minusMillis(1));
        reference = reference.withConfirmedType(DocumentType.PURCHASE_ORDER);
        invoice = invoice.withConfirmedType(DocumentType.INVOICE);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(extracting));
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID))
                .thenReturn(Optional.of(queued));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));

        var result = transaction.queue(ANALYSIS_ID, OWNER, REQUEST_TYPES);

        assertEquals(AnalysisStatus.EXTRACTING, result.status());
        assertEquals(AnalysisJobStatus.QUEUED, result.jobStatus());
        assertEquals(AnalysisStatus.EXTRACTING, result.currentStage());
        verify(documentRepository, never()).updateTypes(any(Document.class));
        verify(analysisRepository, never()).update(any(Analysis.class));
        verify(analysisJobRepository, never()).update(any(AnalysisJob.class));
        verify(extractedDocumentRepository, never()).findByDocumentId(any(UUID.class));
    }

    @Test
    void alreadyQueuedRequestWithDifferentTypesConflictsWithoutMutation() {
        Analysis extracting = classifyingAnalysis.beginExtraction(NOW.minusMillis(1));
        AnalysisJob queued = waitingJob.queueExtraction(NOW.minusMillis(1));
        reference = reference.withConfirmedType(DocumentType.PURCHASE_ORDER);
        invoice = invoice.withConfirmedType(DocumentType.INVOICE);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(extracting));
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID))
                .thenReturn(Optional.of(queued));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));

        assertThrows(ExtractionConflictException.class,
                () -> transaction.queue(ANALYSIS_ID, OWNER,
                        new ExtractionRequestTypes(DocumentType.ESTIMATE, DocumentType.INVOICE)));

        verify(documentRepository, never()).updateTypes(any(Document.class));
        verify(analysisRepository, never()).update(any(Analysis.class));
        verify(analysisJobRepository, never()).update(any(AnalysisJob.class));
    }

    private static Document document(DocumentRole role, DocumentType detectedType, Instant expiresAt) {
        return Document.createUploaded(UUID.randomUUID(), ANALYSIS_ID, role,
                role + ".pdf", "application/pdf", 100, 1, "a".repeat(64),
                "private-key-" + role, expiresAt, NOW.minusSeconds(10)).withDetectedType(detectedType);
    }
}
