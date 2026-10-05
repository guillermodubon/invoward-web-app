package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetExtractionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final UUID REFERENCE_DOCUMENT_ID = UUID.randomUUID();
    private static final UUID INVOICE_DOCUMENT_ID = UUID.randomUUID();
    private static final UUID REFERENCE_EXTRACTION_ID = UUID.randomUUID();
    private static final UUID INVOICE_EXTRACTION_ID = UUID.randomUUID();
    private static final UUID REFERENCE_LINE_ID = UUID.randomUUID();
    private static final UUID INVOICE_LINE_ID = UUID.randomUUID();

    @Mock private GetAnalysisService analysisService;
    @Mock private DocumentRepository documentRepository;
    @Mock private ExtractedDocumentRepository extractedDocumentRepository;

    private GetExtractionService service;
    private Document reference;
    private Document invoice;

    @BeforeEach
    void setUp() {
        service = new GetExtractionService(analysisService, documentRepository, extractedDocumentRepository);
        reference = document(DocumentRole.REFERENCE, REFERENCE_DOCUMENT_ID, DocumentType.PURCHASE_ORDER);
        invoice = document(DocumentRole.INVOICE, INVOICE_DOCUMENT_ID, DocumentType.INVOICE);
    }

    @Test
    void returnsOwnerScopedDraftReviewWithPersistedLineIdentityAndEvidence() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(analysis(AnalysisStatus.AWAITING_CONFIRMATION));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(invoice, reference));
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID))
                .thenReturn(Optional.of(persisted(REFERENCE_EXTRACTION_ID, REFERENCE_DOCUMENT_ID,
                        REFERENCE_LINE_ID, ExtractionStatus.DRAFT)));
        when(extractedDocumentRepository.findByDocumentId(INVOICE_DOCUMENT_ID))
                .thenReturn(Optional.of(persisted(INVOICE_EXTRACTION_ID, INVOICE_DOCUMENT_ID,
                        INVOICE_LINE_ID, ExtractionStatus.DRAFT)));

        ExtractionReview review = service.get(ANALYSIS_ID, OWNER);

        assertEquals(REFERENCE_DOCUMENT_ID, review.reference().documentId());
        assertEquals(DocumentRole.REFERENCE, review.reference().role());
        assertEquals(ExtractionStatus.DRAFT, review.reference().extraction().status());
        assertEquals(List.of(REFERENCE_LINE_ID), review.reference().lineItemIds());
        assertEquals("Printed evidence", review.reference().extraction().lines().getFirst().sourceText());
        assertEquals(INVOICE_DOCUMENT_ID, review.invoice().documentId());
        assertEquals(DocumentRole.INVOICE, review.invoice().role());

        InOrder order = inOrder(analysisService, documentRepository, extractedDocumentRepository);
        order.verify(analysisService).get(ANALYSIS_ID, OWNER);
        order.verify(documentRepository).findByAnalysisId(ANALYSIS_ID);
        order.verify(extractedDocumentRepository).findByDocumentId(REFERENCE_DOCUMENT_ID);
        order.verify(extractedDocumentRepository).findByDocumentId(INVOICE_DOCUMENT_ID);
    }

    @Test
    void confirmedReviewRemainsReadableInLaterAnalysisState() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(analysis(AnalysisStatus.MATCHING));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID))
                .thenReturn(Optional.of(persisted(REFERENCE_EXTRACTION_ID, REFERENCE_DOCUMENT_ID,
                        REFERENCE_LINE_ID, ExtractionStatus.CONFIRMED)));
        when(extractedDocumentRepository.findByDocumentId(INVOICE_DOCUMENT_ID))
                .thenReturn(Optional.of(persisted(INVOICE_EXTRACTION_ID, INVOICE_DOCUMENT_ID,
                        INVOICE_LINE_ID, ExtractionStatus.CONFIRMED)));

        ExtractionReview review = service.get(ANALYSIS_ID, OWNER);

        assertEquals(ExtractionStatus.CONFIRMED, review.reference().extraction().status());
        assertEquals(NOW, review.reference().extraction().confirmedAt());
        assertEquals(ExtractionStatus.CONFIRMED, review.invoice().extraction().status());
        verify(analysisService).get(ANALYSIS_ID, OWNER);
    }

    @Test
    void noExtractionReturnsExtractionNotFound() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(analysis(AnalysisStatus.CLASSIFYING));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID)).thenReturn(Optional.empty());
        when(extractedDocumentRepository.findByDocumentId(INVOICE_DOCUMENT_ID)).thenReturn(Optional.empty());

        assertThrows(ExtractionNotFoundException.class, () -> service.get(ANALYSIS_ID, OWNER));
    }

    @Test
    void partialPairReturnsSafeConflictInsteadOfPartialReview() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(analysis(AnalysisStatus.AWAITING_CONFIRMATION));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID))
                .thenReturn(Optional.of(persisted(REFERENCE_EXTRACTION_ID, REFERENCE_DOCUMENT_ID,
                        REFERENCE_LINE_ID, ExtractionStatus.DRAFT)));
        when(extractedDocumentRepository.findByDocumentId(INVOICE_DOCUMENT_ID)).thenReturn(Optional.empty());

        assertThrows(ExtractionConflictException.class, () -> service.get(ANALYSIS_ID, OWNER));
    }

    @Test
    void ownershipFailureHappensBeforeDocumentOrExtractionReads() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class, () -> service.get(ANALYSIS_ID, OWNER));

        verifyNoInteractions(documentRepository, extractedDocumentRepository);
        verify(analysisService).get(ANALYSIS_ID, OWNER);
    }

    @Test
    void mixedDraftAndConfirmedPairReturnsSafeConflict() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(analysis(AnalysisStatus.AWAITING_CONFIRMATION));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID))
                .thenReturn(Optional.of(persisted(REFERENCE_EXTRACTION_ID, REFERENCE_DOCUMENT_ID,
                        REFERENCE_LINE_ID, ExtractionStatus.DRAFT)));
        when(extractedDocumentRepository.findByDocumentId(INVOICE_DOCUMENT_ID))
                .thenReturn(Optional.of(persisted(INVOICE_EXTRACTION_ID, INVOICE_DOCUMENT_ID,
                        INVOICE_LINE_ID, ExtractionStatus.CONFIRMED)));

        assertThrows(ExtractionConflictException.class, () -> service.get(ANALYSIS_ID, OWNER));
    }

    private static Analysis analysis(AnalysisStatus status) {
        Analysis base = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW.minusSeconds(10));
        return new Analysis(
                base.id(), base.owner(), status, base.reviewStatus(), base.reconciliationStatus(),
                base.supplierName(), base.supplierKey(), base.referenceType(), base.referenceNumber(),
                base.invoiceNumber(), base.currency(), base.referenceTotal(), base.invoicedTotal(),
                base.difference(), base.priceTolerance(), base.retryable(), base.failureCode(),
                base.failureUserMessage(), base.version(), base.completedAt(), base.expiresAt(),
                base.createdAt(), base.updatedAt());
    }

    private static Document document(DocumentRole role, UUID id, DocumentType confirmedType) {
        return Document.createUploaded(id, ANALYSIS_ID, role, role + ".pdf", "application/pdf",
                100, 1, "a".repeat(64), "storage-" + role, null, NOW)
                .withConfirmedType(confirmedType);
    }

    private static PersistedExtraction persisted(
            UUID extractionId, UUID documentId, UUID lineId, ExtractionStatus status) {
        ExtractedLineItem line = new ExtractedLineItem(
                0, "ITEM-1", "Printed evidence", BigDecimal.ONE, "each", BigDecimal.TEN,
                null, null, BigDecimal.TEN, 1, "Printed evidence", new BoundingBox(0.1, 0.2, 0.8, 0.9));
        ExtractedDocument extraction = ExtractedDocument.draft(
                ExtractionSource.AI, "Vendor", "DOC-1", LocalDate.parse("2026-10-01"), "USD",
                BigDecimal.TEN, null, null, BigDecimal.TEN, List.of(line));
        if (status == ExtractionStatus.CONFIRMED) {
            extraction = extraction.confirm(NOW);
        }
        Instant extractedAt = NOW.minusSeconds(5);
        return new PersistedExtraction(extractionId, documentId, extraction, List.of(lineId),
                "document-extraction-v1", "gemini-test", 1,
                status == ExtractionStatus.CONFIRMED ? 1 : 0,
                extractedAt, extractedAt, NOW);
    }
}
