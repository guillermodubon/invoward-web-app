package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.AnalysisExtractionNotAllowedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ConfirmedDocumentTypeInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionAlreadyConfirmedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionReviewException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionConfirmation;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmExtractionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final UUID REFERENCE_ID = UUID.randomUUID();
    private static final UUID INVOICE_ID = UUID.randomUUID();
    private static final UUID REFERENCE_EXTRACTION_ID = UUID.randomUUID();
    private static final UUID INVOICE_EXTRACTION_ID = UUID.randomUUID();
    private static final UUID REFERENCE_LINE_ID = UUID.randomUUID();
    private static final UUID INVOICE_LINE_ID = UUID.randomUUID();

    @Mock private AnalysisRepository analysisRepository;
    @Mock private AnalysisJobRepository analysisJobRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private ExtractedDocumentRepository extractedDocumentRepository;

    private ConfirmExtractionService service;
    private Analysis analysis;
    private AnalysisJob job;
    private Document reference;
    private Document invoice;
    private PersistedExtraction referenceDraft;
    private PersistedExtraction invoiceDraft;

    @BeforeEach
    void setUp() {
        service = new ConfirmExtractionService(analysisRepository, analysisJobRepository,
                documentRepository, extractedDocumentRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        analysis = analysis(AnalysisStatus.AWAITING_CONFIRMATION);
        job = job(AnalysisStatus.AWAITING_CONFIRMATION);
        reference = document(DocumentRole.REFERENCE, REFERENCE_ID, DocumentType.PURCHASE_ORDER);
        invoice = document(DocumentRole.INVOICE, INVOICE_ID, DocumentType.INVOICE);
        referenceDraft = persisted(REFERENCE_EXTRACTION_ID, REFERENCE_ID, REFERENCE_LINE_ID,
                ExtractionSource.AI, "Reference Supplier", "PO-21", "USD", "120.0000");
        invoiceDraft = persisted(INVOICE_EXTRACTION_ID, INVOICE_ID, INVOICE_LINE_ID,
                ExtractionSource.CACHE, " ACME\u00a0  INDUSTRIAL ", "INV-8", "EUR", "132.5000");

        lenient().when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(analysis));
        lenient().when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID))
                .thenReturn(Optional.of(job));
        lenient().when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(invoice, reference));
        lenient().when(extractedDocumentRepository.findByDocumentId(REFERENCE_ID))
                .thenReturn(Optional.of(referenceDraft));
        lenient().when(extractedDocumentRepository.findByDocumentId(INVOICE_ID))
                .thenReturn(Optional.of(invoiceDraft));
        lenient().when(extractedDocumentRepository.confirm(any(), eq(NOW)))
                .thenAnswer(call -> Optional.of(saved(call.getArgument(0))));
        lenient().when(analysisRepository.update(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(analysisJobRepository.update(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void confirmsBothDraftsAndCopiesTheReviewedSummaryWithoutCalculatingDifference() {
        service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0));

        ArgumentCaptor<PersistedExtraction> extractions = ArgumentCaptor.forClass(PersistedExtraction.class);
        verify(extractedDocumentRepository, org.mockito.Mockito.times(2)).confirm(extractions.capture(), eq(NOW));
        assertEquals(ExtractionStatus.CONFIRMED, extractions.getAllValues().get(0).extraction().status());
        assertEquals(NOW, extractions.getAllValues().get(0).extraction().confirmedAt());
        assertEquals(ExtractionStatus.CONFIRMED, extractions.getAllValues().get(1).extraction().status());
        assertEquals(NOW, extractions.getAllValues().get(1).extraction().confirmedAt());

        ArgumentCaptor<Analysis> analysisUpdate = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisRepository).update(analysisUpdate.capture());
        Analysis matching = analysisUpdate.getValue();
        assertEquals(AnalysisStatus.MATCHING, matching.status());
        assertEquals(AnalysisReviewStatus.PENDING, matching.reviewStatus());
        assertNull(matching.reconciliationStatus());
        assertEquals("PURCHASE_ORDER", matching.referenceType());
        assertEquals("PO-21", matching.referenceNumber());
        assertEquals("INV-8", matching.invoiceNumber());
        assertEquals("ACME\u00a0  INDUSTRIAL", matching.supplierName());
        assertEquals("acme industrial", matching.supplierKey());
        assertEquals("EUR", matching.currency());
        assertEquals(new BigDecimal("120.0000"), matching.referenceTotal());
        assertEquals(new BigDecimal("132.5000"), matching.invoicedTotal());
        assertNull(matching.difference());
        assertNull(matching.completedAt());

        ArgumentCaptor<AnalysisJob> jobUpdate = ArgumentCaptor.forClass(AnalysisJob.class);
        verify(analysisJobRepository).update(jobUpdate.capture());
        AnalysisJob matchingJob = jobUpdate.getValue();
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, matchingJob.status());
        assertEquals(AnalysisStatus.MATCHING, matchingJob.currentStage());
        assertEquals(0, matchingJob.attemptCount());
        assertNull(matchingJob.startedAt());
        assertNull(matchingJob.completedAt());
    }

    @Test
    void usesReferenceSupplierAndCurrencyOnlyWhenInvoiceValuesAreAbsent() {
        invoiceDraft = persisted(INVOICE_EXTRACTION_ID, INVOICE_ID, INVOICE_LINE_ID,
                ExtractionSource.AI, null, "INV-8", null, "132.5000");
        when(extractedDocumentRepository.findByDocumentId(INVOICE_ID)).thenReturn(Optional.of(invoiceDraft));

        service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0));

        ArgumentCaptor<Analysis> update = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisRepository).update(update.capture());
        assertEquals("Reference Supplier", update.getValue().supplierName());
        assertEquals("reference supplier", update.getValue().supplierKey());
        assertEquals("USD", update.getValue().currency());
    }

    @Test
    void crossOwnerAnalysisReturnsNotFoundBeforeReadingJobOrDocuments() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));

        verifyNoInteractions(analysisJobRepository, documentRepository, extractedDocumentRepository);
    }

    @Test
    void analysisMustBeAwaitingHumanConfirmation() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(analysis(AnalysisStatus.CLASSIFYING)));

        assertThrows(AnalysisExtractionNotAllowedException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));

        verifyNoInteractions(analysisJobRepository, documentRepository, extractedDocumentRepository);
    }

    @Test
    void repeatedConfirmationIsRejectedWithoutReadingDocuments() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(analysis(AnalysisStatus.MATCHING)));

        assertThrows(ExtractionAlreadyConfirmedException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));

        verifyNoInteractions(documentRepository, extractedDocumentRepository);
    }

    @Test
    void requiresExactlyOneReferenceAndOneInvoice() {
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference));

        assertThrows(DocumentsRequiredException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));

        verifyNoInteractions(extractedDocumentRepository);
    }

    @Test
    void jobMustStillBeWaitingAtExtractionConfirmation() {
        when(analysisJobRepository.findJobByAnalysisIdForUpdate(ANALYSIS_ID))
                .thenReturn(Optional.of(job(AnalysisStatus.CLASSIFYING)));

        assertThrows(ExtractionConflictException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));

        verifyNoInteractions(documentRepository, extractedDocumentRepository);
    }

    @Test
    void documentTypesMustHaveBeenHumanConfirmed() {
        reference = document(DocumentRole.REFERENCE, REFERENCE_ID, null);
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));

        assertThrows(ConfirmedDocumentTypeInvalidException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));

        verifyNoInteractions(extractedDocumentRepository);
    }

    @Test
    void missingDraftsReturnNotFoundAndPartialDraftPairConflicts() {
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_ID)).thenReturn(Optional.empty());
        when(extractedDocumentRepository.findByDocumentId(INVOICE_ID)).thenReturn(Optional.empty());
        assertThrows(ExtractionNotFoundException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));

        when(extractedDocumentRepository.findByDocumentId(REFERENCE_ID)).thenReturn(Optional.of(referenceDraft));
        assertThrows(ExtractionConflictException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));
        verify(extractedDocumentRepository, never()).confirm(any(), any());
    }

    @Test
    void staleVersionAndWrongDocumentIdentityAreRejectedBeforeWrites() {
        assertThrows(ExtractionConflictException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(1, 0)));
        assertThrows(ExtractionConflictException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, new ExtractionConfirmation(
                        UUID.randomUUID(), 0, INVOICE_ID, 0)));

        verify(extractedDocumentRepository, never()).confirm(any(), any());
    }

    @Test
    void bothDraftsNeedAtLeastOneLine() {
        referenceDraft = persisted(REFERENCE_EXTRACTION_ID, REFERENCE_ID, REFERENCE_LINE_ID,
                ExtractionSource.AI, "Reference Supplier", "PO-21", "USD", "120.0000", List.of());
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_ID)).thenReturn(Optional.of(referenceDraft));

        assertThrows(InvalidExtractionReviewException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));

        verify(extractedDocumentRepository, never()).confirm(any(), any());
    }

    @Test
    void partialAlreadyConfirmedStateConflictsAndRaceOnSecondWriteDoesNotUpdateWorkflow() {
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_ID))
                .thenReturn(Optional.of(confirmed(referenceDraft)));
        assertThrows(ExtractionConflictException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(1, 0)));

        when(extractedDocumentRepository.findByDocumentId(REFERENCE_ID)).thenReturn(Optional.of(referenceDraft));
        when(extractedDocumentRepository.confirm(any(), eq(NOW))).thenAnswer(call -> {
            PersistedExtraction candidate = call.getArgument(0);
            return candidate.documentId().equals(REFERENCE_ID)
                    ? Optional.of(saved(candidate)) : Optional.empty();
        });
        assertThrows(ExtractionConflictException.class,
                () -> service.confirm(ANALYSIS_ID, OWNER, confirmation(0, 0)));

        verify(analysisRepository, never()).update(any());
        verify(analysisJobRepository, never()).update(any());
    }

    private ExtractionConfirmation confirmation(long referenceVersion, long invoiceVersion) {
        return new ExtractionConfirmation(REFERENCE_ID, referenceVersion, INVOICE_ID, invoiceVersion);
    }

    private static Analysis analysis(AnalysisStatus status) {
        Analysis base = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW.minusSeconds(10));
        return new Analysis(base.id(), base.owner(), status, base.reviewStatus(), base.reconciliationStatus(),
                base.supplierName(), base.supplierKey(), base.referenceType(), base.referenceNumber(),
                base.invoiceNumber(), base.currency(), base.referenceTotal(), base.invoicedTotal(), base.difference(),
                base.priceTolerance(), base.retryable(), base.failureCode(), base.failureUserMessage(), base.version(),
                base.completedAt(), base.expiresAt(), base.createdAt(), NOW.minusSeconds(1));
    }

    private static AnalysisJob job(AnalysisStatus currentStage) {
        Instant created = NOW.minusSeconds(10);
        return new AnalysisJob(UUID.randomUUID(), ANALYSIS_ID, AnalysisJobStatus.WAITING_FOR_USER,
                currentStage, 0, false, null, null, null, null, created, NOW.minusSeconds(1));
    }

    private static Document document(DocumentRole role, UUID id, DocumentType confirmedType) {
        Document document = Document.createUploaded(id, ANALYSIS_ID, role, role + ".pdf", "application/pdf",
                100, 1, "a".repeat(64), "confirm-test/" + role, null, NOW.minusSeconds(10));
        return confirmedType == null ? document : document.withConfirmedType(confirmedType);
    }

    private static PersistedExtraction persisted(
            UUID extractionId,
            UUID documentId,
            UUID lineId,
            ExtractionSource source,
            String vendor,
            String number,
            String currency,
            String total) {
        return persisted(extractionId, documentId, lineId, source, vendor, number, currency, total,
                List.of(line(0, "Item " + number)));
    }

    private static PersistedExtraction persisted(
            UUID extractionId,
            UUID documentId,
            UUID lineId,
            ExtractionSource source,
            String vendor,
            String number,
            String currency,
            String total,
            List<ExtractedLineItem> lines) {
        ExtractedDocument extraction = ExtractedDocument.draft(source, vendor, number,
                LocalDate.parse("2026-10-01"), currency, new BigDecimal(total), null, null,
                new BigDecimal(total), lines);
        return new PersistedExtraction(extractionId, documentId, extraction,
                lines.isEmpty() ? List.of() : List.of(lineId), "document-extraction-v1", "gemini-test", 1,
                0, NOW.minusSeconds(5), NOW.minusSeconds(5), NOW.minusSeconds(1));
    }

    private static PersistedExtraction confirmed(PersistedExtraction draft) {
        return new PersistedExtraction(draft.id(), draft.documentId(), draft.extraction().confirm(NOW),
                draft.lineItemIds(), draft.extractorVersion(), draft.modelId(), draft.schemaVersion(),
                draft.version() + 1, draft.extractedAt(), draft.createdAt(), NOW);
    }

    private static PersistedExtraction saved(PersistedExtraction candidate) {
        return new PersistedExtraction(candidate.id(), candidate.documentId(), candidate.extraction(),
                candidate.lineItemIds(), candidate.extractorVersion(), candidate.modelId(), candidate.schemaVersion(),
                candidate.version() + 1, candidate.extractedAt(), candidate.createdAt(), NOW);
    }

    private static ExtractedLineItem line(int position, String description) {
        return new ExtractedLineItem(position, null, description, BigDecimal.ONE, "each", BigDecimal.TEN,
                null, null, BigDecimal.TEN, 1, "Printed: " + description, null);
    }
}
