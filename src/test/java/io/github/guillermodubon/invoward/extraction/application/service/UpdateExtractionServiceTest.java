package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
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
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionLockedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionReviewException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReviewUpdate;
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
class UpdateExtractionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final UUID REFERENCE_DOCUMENT_ID = UUID.randomUUID();
    private static final UUID INVOICE_DOCUMENT_ID = UUID.randomUUID();
    private static final UUID REFERENCE_EXTRACTION_ID = UUID.randomUUID();
    private static final UUID INVOICE_EXTRACTION_ID = UUID.randomUUID();
    private static final UUID REFERENCE_LINE_ONE = UUID.randomUUID();
    private static final UUID REFERENCE_LINE_TWO = UUID.randomUUID();
    private static final UUID REFERENCE_LINE_REMOVED = UUID.randomUUID();
    private static final UUID INVOICE_LINE = UUID.randomUUID();
    private static final BoundingBox EVIDENCE_BOX = new BoundingBox(0.1, 0.2, 0.8, 0.9);

    @Mock private AnalysisRepository analysisRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private ExtractedDocumentRepository extractedDocumentRepository;

    private UpdateExtractionService service;
    private Document reference;
    private Document invoice;
    private PersistedExtraction referenceDraft;
    private PersistedExtraction invoiceDraft;

    @BeforeEach
    void setUp() {
        service = new UpdateExtractionService(analysisRepository, documentRepository,
                extractedDocumentRepository, Clock.fixed(NOW, ZoneOffset.UTC), 5);
        reference = document(DocumentRole.REFERENCE, REFERENCE_DOCUMENT_ID, DocumentType.PURCHASE_ORDER);
        invoice = document(DocumentRole.INVOICE, INVOICE_DOCUMENT_ID, DocumentType.INVOICE);
        referenceDraft = persisted(REFERENCE_EXTRACTION_ID, reference, ExtractionStatus.DRAFT,
                List.of(REFERENCE_LINE_ONE, REFERENCE_LINE_TWO, REFERENCE_LINE_REMOVED),
                List.of(line(0, "A", "Original A"), line(1, "B", "Original B"), line(2, "C", "Removed C")));
        invoiceDraft = persisted(INVOICE_EXTRACTION_ID, invoice, ExtractionStatus.DRAFT,
                List.of(INVOICE_LINE), List.of(line(0, "I", "Invoice line")));
        lenient().when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(analysis(AnalysisStatus.AWAITING_CONFIRMATION)));
        lenient().when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(invoice, reference));
        lenient().when(documentRepository.updateTypes(any())).thenAnswer(call -> Optional.of(call.getArgument(0)));
        lenient().when(extractedDocumentRepository.update(any(), eq(NOW)))
                .thenAnswer(call -> Optional.of(saved(call.getArgument(0))));
    }

    @Test
    void fullReplacementUpdatesHeadersTypesLinesAndEvidenceInRequestOrder() {
        arrangeDrafts();
        ExtractionReviewUpdate request = new ExtractionReviewUpdate(List.of(
                documentUpdate(invoice, 0, DocumentType.INVOICE, List.of(
                        lineUpdate(INVOICE_LINE, "I", "Invoice line"))),
                withHeaders(documentUpdate(reference, 0, DocumentType.QUOTE, List.of(
                        lineUpdate(REFERENCE_LINE_TWO, "B", "Original B"),
                        lineUpdate(REFERENCE_LINE_ONE, "A", "Corrected A"),
                        lineUpdate(null, "D", "Manual line"))),
                        "Corrected Vendor", "PO-2", LocalDate.parse("2026-10-03"), "eur",
                        amount("90"), amount("1"), amount("8"), amount("97"))));

        ExtractionReview updated = service.update(ANALYSIS_ID, OWNER, request);

        assertEquals("Corrected Vendor", updated.reference().extraction().vendorName());
        assertEquals("EUR", updated.reference().extraction().currency());
        assertEquals(DocumentType.QUOTE, updated.reference().confirmedType());
        assertEquals(1, updated.reference().version());
        assertEquals(3, updated.reference().extraction().lines().size());
        ExtractedLineItem unchanged = updated.reference().extraction().lines().get(0);
        assertEquals("Original B", unchanged.description());
        assertEquals(1, unchanged.pageNumber());
        assertEquals("Printed B", unchanged.sourceText());
        assertEquals(EVIDENCE_BOX, unchanged.boundingBox());
        ExtractedLineItem edited = updated.reference().extraction().lines().get(1);
        assertEquals("Corrected A", edited.description());
        assertNull(edited.pageNumber());
        assertNull(edited.sourceText());
        assertNull(edited.boundingBox());
        ExtractedLineItem added = updated.reference().extraction().lines().get(2);
        assertEquals("Manual line", added.description());
        assertNull(added.pageNumber());
        assertNull(added.sourceText());
        assertNull(added.boundingBox());
        assertEquals("manual line", added.normalizedDescription());

        ArgumentCaptor<PersistedExtraction> updates = ArgumentCaptor.forClass(PersistedExtraction.class);
        verify(extractedDocumentRepository, org.mockito.Mockito.times(2)).update(updates.capture(), eq(NOW));
        PersistedExtraction savedReference = updates.getAllValues().get(0);
        assertEquals(List.of(REFERENCE_LINE_TWO, REFERENCE_LINE_ONE),
                savedReference.lineItemIds().subList(0, 2));
        assertEquals(3, savedReference.extraction().lines().size());
        verify(documentRepository).updateTypes(reference.withConfirmedType(DocumentType.QUOTE));
        verify(documentRepository, never()).updateTypes(invoice);
    }

    @Test
    void staleExpectedVersionIsRejectedBeforeAnyUpdate() {
        arrangeDrafts();
        ExtractionReviewUpdate request = request(
                documentUpdate(reference, 1, DocumentType.PURCHASE_ORDER,
                        List.of(lineUpdate(REFERENCE_LINE_ONE, "A", "Original A"),
                                lineUpdate(REFERENCE_LINE_TWO, "B", "Original B"),
                                lineUpdate(REFERENCE_LINE_REMOVED, "C", "Removed C"))),
                documentUpdate(invoice, 0, DocumentType.INVOICE, List.of(lineUpdate(INVOICE_LINE, "I", "Invoice line"))));

        assertThrows(ExtractionConflictException.class, () -> service.update(ANALYSIS_ID, OWNER, request));

        verify(extractedDocumentRepository, never()).update(any(), any());
        verify(documentRepository, never()).updateTypes(any());
    }

    @Test
    void confirmedExtractionIsLocked() {
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID))
                .thenReturn(Optional.of(withStatus(referenceDraft, ExtractionStatus.CONFIRMED)));
        when(extractedDocumentRepository.findByDocumentId(INVOICE_DOCUMENT_ID)).thenReturn(Optional.of(invoiceDraft));

        assertThrows(ExtractionLockedException.class, () -> service.update(ANALYSIS_ID, OWNER,
                request(minimalUpdate(reference, referenceDraft), minimalUpdate(invoice, invoiceDraft))));

        verify(extractedDocumentRepository, never()).update(any(), any());
    }

    @Test
    void roleIncompatibleConfirmedTypeIsRejected() {
        arrangeDrafts();

        assertThrows(ConfirmedDocumentTypeInvalidException.class, () -> service.update(ANALYSIS_ID, OWNER,
                request(documentUpdate(invoice, 0, DocumentType.QUOTE, List.of(lineUpdate(INVOICE_LINE, "I", "Invoice line"))),
                        minimalUpdate(reference, referenceDraft))));

        verify(extractedDocumentRepository, never()).update(any(), any());
    }

    @Test
    void invalidMoneyOrQuantityIsRejectedWithoutPersisting() {
        arrangeDrafts();
        ExtractionReviewUpdate invalid = request(
                documentUpdate(reference, 0, DocumentType.PURCHASE_ORDER, List.of(
                        new ExtractionReviewUpdate.LineUpdate(REFERENCE_LINE_ONE, "A", "Original A",
                                BigDecimal.ZERO, "each", BigDecimal.TEN, null, null, BigDecimal.TEN))),
                minimalUpdate(invoice, invoiceDraft));

        assertThrows(InvalidExtractionReviewException.class, () -> service.update(ANALYSIS_ID, OWNER, invalid));
        verify(extractedDocumentRepository, never()).update(any(), any());
    }

    @Test
    void negativeHeaderAmountIsRejectedWithoutPersisting() {
        arrangeDrafts();
        ExtractionReviewUpdate.DocumentUpdate invalidReference = withHeaders(
                minimalUpdate(reference, referenceDraft), "Vendor", "DOC-1", LocalDate.parse("2026-10-01"),
                "USD", amount("-1"), null, null, amount("10"));

        assertThrows(InvalidExtractionReviewException.class, () -> service.update(ANALYSIS_ID, OWNER,
                request(invalidReference, minimalUpdate(invoice, invoiceDraft))));
        verify(extractedDocumentRepository, never()).update(any(), any());
    }

    @Test
    void lineCountCannotExceedConfiguredMaximum() {
        arrangeDrafts();
        service = new UpdateExtractionService(analysisRepository, documentRepository,
                extractedDocumentRepository, Clock.fixed(NOW, ZoneOffset.UTC), 2);
        ExtractionReviewUpdate tooMany = request(
                documentUpdate(reference, 0, DocumentType.PURCHASE_ORDER, List.of(
                        lineUpdate(REFERENCE_LINE_ONE, "A", "Original A"),
                        lineUpdate(REFERENCE_LINE_TWO, "B", "Original B"),
                        lineUpdate(REFERENCE_LINE_REMOVED, "C", "Removed C"))),
                minimalUpdate(invoice, invoiceDraft));

        assertThrows(InvalidExtractionReviewException.class, () -> service.update(ANALYSIS_ID, OWNER, tooMany));
        verify(extractedDocumentRepository, never()).update(any(), any());
    }

    @Test
    void unknownLineIdCannotBeUsedAsIfItBelongedToTheDocument() {
        arrangeDrafts();
        ExtractionReviewUpdate invalid = request(
                documentUpdate(reference, 0, DocumentType.PURCHASE_ORDER,
                        List.of(lineUpdate(UUID.randomUUID(), "A", "Forged reference"))),
                minimalUpdate(invoice, invoiceDraft));

        assertThrows(InvalidExtractionReviewException.class, () -> service.update(ANALYSIS_ID, OWNER, invalid));
        verify(extractedDocumentRepository, never()).update(any(), any());
    }

    @Test
    void crossOwnerAnalysisReturnsNotFoundBeforeReadingDocuments() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> service.update(ANALYSIS_ID, OWNER,
                request(minimalUpdate(reference, referenceDraft), minimalUpdate(invoice, invoiceDraft))));

        verifyNoInteractions(documentRepository, extractedDocumentRepository);
    }

    @Test
    void wrongAnalysisStateIsRejectedBeforeReadingDocuments() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(analysis(AnalysisStatus.CLASSIFYING)));

        assertThrows(AnalysisExtractionNotAllowedException.class, () -> service.update(ANALYSIS_ID, OWNER,
                request(minimalUpdate(reference, referenceDraft), minimalUpdate(invoice, invoiceDraft))));

        verifyNoInteractions(documentRepository, extractedDocumentRepository);
    }

    @Test
    void missingPairReturnsSafeExtractionNotFound() {
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID)).thenReturn(Optional.empty());
        when(extractedDocumentRepository.findByDocumentId(INVOICE_DOCUMENT_ID)).thenReturn(Optional.empty());

        assertThrows(ExtractionNotFoundException.class, () -> service.update(ANALYSIS_ID, OWNER,
                request(minimalUpdate(reference, referenceDraft), minimalUpdate(invoice, invoiceDraft))));
    }

    @Test
    void optimisticRaceFromRepositoryIsReturnedAsConflict() {
        arrangeDrafts();
        when(extractedDocumentRepository.update(any(), eq(NOW)))
                .thenAnswer(call -> {
                    PersistedExtraction candidate = call.getArgument(0);
                    return candidate.documentId().equals(REFERENCE_DOCUMENT_ID)
                            ? Optional.of(saved(candidate)) : Optional.empty();
                });

        assertThrows(ExtractionConflictException.class, () -> service.update(ANALYSIS_ID, OWNER,
                request(minimalUpdate(reference, referenceDraft), minimalUpdate(invoice, invoiceDraft))));
    }

    private void arrangeDrafts() {
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID))
                .thenReturn(Optional.of(referenceDraft));
        when(extractedDocumentRepository.findByDocumentId(INVOICE_DOCUMENT_ID))
                .thenReturn(Optional.of(invoiceDraft));
    }

    private static ExtractionReviewUpdate request(
            ExtractionReviewUpdate.DocumentUpdate reference,
            ExtractionReviewUpdate.DocumentUpdate invoice) {
        return new ExtractionReviewUpdate(List.of(reference, invoice));
    }

    private static ExtractionReviewUpdate.DocumentUpdate minimalUpdate(
            Document document, PersistedExtraction extraction) {
        return documentUpdate(document, extraction.version(), document.confirmedType(),
                extraction.extraction().lines().stream()
                        .map(line -> lineUpdate(extraction.lineItemIds().get(line.position()),
                                line.itemCode(), line.description()))
                        .toList());
    }

    private static ExtractionReviewUpdate.DocumentUpdate documentUpdate(
            Document document,
            long version,
            DocumentType type,
            List<ExtractionReviewUpdate.LineUpdate> lines) {
        return new ExtractionReviewUpdate.DocumentUpdate(document.id(), version, type,
                "Vendor", "DOC-1", LocalDate.parse("2026-10-01"), "USD",
                amount("10"), null, null, amount("10"), lines);
    }

    private static ExtractionReviewUpdate.DocumentUpdate withHeaders(
            ExtractionReviewUpdate.DocumentUpdate source,
            String vendor,
            String number,
            LocalDate date,
            String currency,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal tax,
            BigDecimal total) {
        return new ExtractionReviewUpdate.DocumentUpdate(source.documentId(), source.expectedVersion(),
                source.confirmedType(), vendor, number, date, currency, subtotal, discount, tax, total,
                source.lines());
    }

    private static ExtractionReviewUpdate.LineUpdate lineUpdate(UUID id, String code, String description) {
        return new ExtractionReviewUpdate.LineUpdate(id, code, description, BigDecimal.ONE, "each",
                BigDecimal.TEN, null, null, BigDecimal.TEN);
    }

    private static Document document(DocumentRole role, UUID id, DocumentType type) {
        return Document.createUploaded(id, ANALYSIS_ID, role, role + ".pdf", "application/pdf",
                100, 1, "a".repeat(64), "storage-" + role, null, NOW)
                .withConfirmedType(type);
    }

    private static PersistedExtraction persisted(
            UUID extractionId,
            Document document,
            ExtractionStatus status,
            List<UUID> lineIds,
            List<ExtractedLineItem> lines) {
        ExtractedDocument extraction = ExtractedDocument.draft(ExtractionSource.AI,
                "Vendor", "DOC-1", LocalDate.parse("2026-10-01"), "USD",
                amount("10"), null, null, amount("10"), lines);
        if (status == ExtractionStatus.CONFIRMED) {
            extraction = extraction.confirm(NOW);
        }
        return new PersistedExtraction(extractionId, document.id(), extraction, lineIds,
                "document-extraction-v1", "gemini-test", 1, status == ExtractionStatus.DRAFT ? 0 : 1,
                NOW.minusSeconds(5), NOW.minusSeconds(5), NOW.minusSeconds(1));
    }

    private static PersistedExtraction withStatus(PersistedExtraction source, ExtractionStatus status) {
        ExtractedDocument extraction = source.extraction().confirm(NOW);
        return new PersistedExtraction(source.id(), source.documentId(), extraction, source.lineItemIds(),
                source.extractorVersion(), source.modelId(), source.schemaVersion(), source.version() + 1,
                source.extractedAt(), source.createdAt(), NOW);
    }

    private static PersistedExtraction saved(PersistedExtraction source) {
        return new PersistedExtraction(source.id(), source.documentId(), source.extraction(),
                source.lineItemIds(), source.extractorVersion(), source.modelId(), source.schemaVersion(),
                source.version() + 1, source.extractedAt(), source.createdAt(), NOW);
    }

    private static ExtractedLineItem line(int position, String code, String description) {
        return new ExtractedLineItem(position, code, description, BigDecimal.ONE, "each", BigDecimal.TEN,
                null, null, BigDecimal.TEN, 1, "Printed " + code, EVIDENCE_BOX);
    }

    private static Analysis analysis(AnalysisStatus status) {
        Analysis base = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW.minusSeconds(10));
        return new Analysis(base.id(), base.owner(), status, base.reviewStatus(), base.reconciliationStatus(),
                base.supplierName(), base.supplierKey(), base.referenceType(), base.referenceNumber(),
                base.invoiceNumber(), base.currency(), base.referenceTotal(), base.invoicedTotal(),
                base.difference(), base.priceTolerance(), base.retryable(), base.failureCode(),
                base.failureUserMessage(), base.version(), base.completedAt(), base.expiresAt(),
                base.createdAt(), base.updatedAt());
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

}
