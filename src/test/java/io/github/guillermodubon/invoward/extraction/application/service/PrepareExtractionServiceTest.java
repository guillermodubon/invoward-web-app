package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDocumentPair;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionPreparation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrepareExtractionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final String MODEL = "gemini-test-model";

    @Mock private GetAnalysisService analysisService;
    @Mock private DocumentRepository documentRepository;
    @Mock private ExtractedDocumentRepository extractedDocumentRepository;
    @Mock private ConfirmExtractionTypesTransaction confirmTypesTransaction;
    @Mock private ExtractionCacheService cacheService;
    @Mock private DocumentStorage documentStorage;
    @Mock private DocumentIntelligence documentIntelligence;

    private PrepareExtractionService service;
    private Document reference;
    private Document invoice;
    private Document confirmedReference;
    private Document confirmedInvoice;

    @BeforeEach
    void setUp() {
        service = new PrepareExtractionService(
                analysisService, documentRepository, extractedDocumentRepository,
                confirmTypesTransaction, cacheService, new DocumentIntelligenceFileMaterializer(documentStorage),
                documentIntelligence, MODEL, Clock.fixed(NOW, ZoneOffset.UTC));
        Analysis classifying = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW)
                .transitionToUploading(NOW.plusSeconds(1))
                .markClassifying(NOW.plusSeconds(2));
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(classifying);
        reference = document(DocumentRole.REFERENCE, DocumentType.INVOICE, "a".repeat(64));
        invoice = document(DocumentRole.INVOICE, DocumentType.QUOTE, "b".repeat(64));
        confirmedReference = reference.withConfirmedType(DocumentType.PURCHASE_ORDER);
        confirmedInvoice = invoice.withConfirmedType(DocumentType.INVOICE);
        lenient().when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(invoice, reference));
        lenient().when(extractedDocumentRepository.findByDocumentId(reference.id())).thenReturn(Optional.empty());
        lenient().when(extractedDocumentRepository.findByDocumentId(invoice.id())).thenReturn(Optional.empty());
        lenient().when(confirmTypesTransaction.confirm(ANALYSIS_ID, OWNER, requestTypes()))
                .thenReturn(new ExtractionDocumentPair(confirmedReference, confirmedInvoice));
    }

    @Test
    void bothCacheHitsAvoidStorageAndProviderAndReturnValidatedCacheResults() {
        when(cacheService.findValid(reference.sha256(), MODEL, 1)).thenReturn(Optional.of(cacheDraft("REF")));
        when(cacheService.findValid(invoice.sha256(), MODEL, 1)).thenReturn(Optional.of(cacheDraft("INV")));

        ExtractionPreparation.Ready result = (ExtractionPreparation.Ready) service.prepare(
                ANALYSIS_ID, OWNER, requestTypes());

        assertEquals(ExtractionSource.CACHE, result.referenceExtraction().extractionSource());
        assertEquals(ExtractionSource.CACHE, result.invoiceExtraction().extractionSource());
        verify(confirmTypesTransaction).confirm(ANALYSIS_ID, OWNER, requestTypes());
        verifyNoInteractions(documentStorage, documentIntelligence);
    }

    @Test
    void referenceCacheHitAndInvoiceProviderMissUseTheConfiguredModelAndConfirmedTypes() {
        when(cacheService.findValid(reference.sha256(), MODEL, 1)).thenReturn(Optional.of(cacheDraft("REF")));
        when(cacheService.findValid(invoice.sha256(), MODEL, 1)).thenReturn(Optional.empty());
        when(documentIntelligence.extract(any(), eq(DocumentType.INVOICE))).thenReturn(validDraft("AI invoice"));
        when(cacheService.cacheProviderOutput(invoice.sha256(), MODEL, validDraft("AI invoice"), 1))
                .thenReturn(aiDraft("AI invoice"));
        materialize(invoice);

        ExtractionPreparation.Ready result = (ExtractionPreparation.Ready) service.prepare(
                ANALYSIS_ID, OWNER, requestTypes());

        assertEquals(ExtractionSource.CACHE, result.referenceExtraction().extractionSource());
        assertEquals(ExtractionSource.AI, result.invoiceExtraction().extractionSource());
        verify(documentIntelligence).extract(any(), eq(DocumentType.INVOICE));
        verify(documentIntelligence, never()).extract(any(), eq(DocumentType.PURCHASE_ORDER));
    }

    @Test
    void referenceProviderMissAndInvoiceCacheHitUseBothPaths() {
        when(cacheService.findValid(reference.sha256(), MODEL, 1)).thenReturn(Optional.empty());
        when(cacheService.findValid(invoice.sha256(), MODEL, 1)).thenReturn(Optional.of(cacheDraft("INV")));
        when(documentIntelligence.extract(any(), eq(DocumentType.PURCHASE_ORDER)))
                .thenReturn(validDraft("AI reference"));
        when(cacheService.cacheProviderOutput(reference.sha256(), MODEL, validDraft("AI reference"), 1))
                .thenReturn(aiDraft("AI reference"));
        materialize(reference);

        ExtractionPreparation.Ready result = (ExtractionPreparation.Ready) service.prepare(
                ANALYSIS_ID, OWNER, requestTypes());

        assertEquals(ExtractionSource.AI, result.referenceExtraction().extractionSource());
        assertEquals(ExtractionSource.CACHE, result.invoiceExtraction().extractionSource());
        verify(documentIntelligence).extract(any(), eq(DocumentType.PURCHASE_ORDER));
    }

    @Test
    void bothProviderMissesValidateAndReturnBothDrafts() {
        when(cacheService.findValid(any(), eq(MODEL), eq(1))).thenReturn(Optional.empty());
        when(documentIntelligence.extract(any(), any()))
                .thenReturn(validDraft("reference"), validDraft("invoice"));
        when(cacheService.cacheProviderOutput(any(), eq(MODEL), any(), eq(1)))
                .thenReturn(aiDraft("reference"), aiDraft("invoice"));
        materialize(reference);
        materialize(invoice);

        ExtractionPreparation.Ready result = (ExtractionPreparation.Ready) service.prepare(
                ANALYSIS_ID, OWNER, requestTypes());

        assertEquals(ExtractionSource.AI, result.referenceExtraction().extractionSource());
        assertEquals(ExtractionSource.AI, result.invoiceExtraction().extractionSource());
        verify(documentIntelligence, org.mockito.Mockito.times(2)).extract(any(), any());
    }

    @Test
    void secondProviderFailureLeavesConfirmedTypesForRetryAndCreatesNoExtractionRows() {
        when(cacheService.findValid(any(), eq(MODEL), eq(1))).thenReturn(Optional.empty());
        when(documentIntelligence.extract(any(), any()))
                .thenReturn(validDraft("reference"))
                .thenThrow(new DocumentIntelligenceException(DocumentIntelligenceException.Failure.UNAVAILABLE));
        when(cacheService.cacheProviderOutput(reference.sha256(), MODEL, validDraft("reference"), 1))
                .thenReturn(aiDraft("reference"));
        materialize(reference);
        materialize(invoice);

        assertThrows(DocumentIntelligenceException.class,
                () -> service.prepare(ANALYSIS_ID, OWNER, requestTypes()));

        verify(confirmTypesTransaction).confirm(ANALYSIS_ID, OWNER, requestTypes());
        verify(extractedDocumentRepository, never()).create(any(), any(), any(), any(), any(int.class), any());
        verify(cacheService).cacheProviderOutput(reference.sha256(), MODEL, validDraft("reference"), 1);
    }

    @Test
    void existingPairOfDraftsIsReturnedWithoutReconfirmingOrCallingAi() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(withStatus(
                Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW),
                AnalysisStatus.AWAITING_CONFIRMATION));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(List.of(confirmedInvoice, confirmedReference));
        PersistedExtraction referenceDraft = persisted(reference, ExtractionSource.AI);
        PersistedExtraction invoiceDraft = persisted(invoice, ExtractionSource.CACHE);
        when(extractedDocumentRepository.findByDocumentId(reference.id())).thenReturn(Optional.of(referenceDraft));
        when(extractedDocumentRepository.findByDocumentId(invoice.id())).thenReturn(Optional.of(invoiceDraft));

        ExtractionPreparation result = service.prepare(ANALYSIS_ID, OWNER, requestTypes());

        ExtractionReview review = assertInstanceOf(
                ExtractionPreparation.ExistingDraft.class, result).review();
        assertEquals(ExtractionSource.AI, review.reference().extraction().extractionSource());
        assertEquals(ExtractionSource.CACHE, review.invoice().extraction().extractionSource());
        verifyNoInteractions(confirmTypesTransaction, cacheService, documentStorage, documentIntelligence);
    }

    @Test
    void partialPersistedDraftFailsSafelyWithoutCallingProviders() {
        when(extractedDocumentRepository.findByDocumentId(reference.id()))
                .thenReturn(Optional.of(persisted(reference, ExtractionSource.AI)));

        assertThrows(ExtractionConflictException.class,
                () -> service.prepare(ANALYSIS_ID, OWNER, requestTypes()));

        verifyNoInteractions(confirmTypesTransaction, cacheService, documentStorage, documentIntelligence);
    }

    @Test
    void wrongOwnerIsRejectedBeforeReadingDocumentsOrCache() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class,
                () -> service.prepare(ANALYSIS_ID, OWNER, requestTypes()));

        verifyNoInteractions(documentRepository, extractedDocumentRepository, confirmTypesTransaction,
                cacheService, documentStorage, documentIntelligence);
    }

    private void materialize(Document document) {
        doNothing().when(documentStorage).downloadTo(eq(document.storageKey()), any(Path.class));
    }

    private static ExtractionRequestTypes requestTypes() {
        return new ExtractionRequestTypes(DocumentType.PURCHASE_ORDER, DocumentType.INVOICE);
    }

    private static Document document(DocumentRole role, DocumentType detectedType, String sha256) {
        return Document.createUploaded(UUID.randomUUID(), ANALYSIS_ID, role,
                role + ".pdf", "application/pdf", 100, 1, sha256,
                "private-key-" + role, null, NOW).withDetectedType(detectedType);
    }

    private static ExtractionDraft validDraft(String vendor) {
        return new ExtractionDraft(vendor, null, null, "USD", null, null, null, null,
                List.of(new ExtractionDraft.Line(null, "Service", null, null, null,
                        null, null, null, 1, "Service", null)));
    }

    private static ExtractedDocument cacheDraft(String vendor) {
        return ExtractedDocument.draft(ExtractionSource.CACHE, vendor, null, null, "USD",
                null, null, null, null, List.of());
    }

    private static ExtractedDocument aiDraft(String vendor) {
        return ExtractedDocument.draft(ExtractionSource.AI, vendor, null, null, "USD",
                null, null, null, null, List.of());
    }

    private static PersistedExtraction persisted(Document document, ExtractionSource source) {
        Instant timestamp = NOW.minusSeconds(10);
        ExtractedDocument draft = ExtractedDocument.draft(source, "Vendor", null, null, "USD",
                null, null, null, null, List.of());
        return new PersistedExtraction(UUID.randomUUID(), document.id(), draft,
                List.of(), "document-extraction-v1", MODEL, 1, 0, timestamp, timestamp, timestamp);
    }

    private static Analysis withStatus(Analysis source, AnalysisStatus status) {
        return new Analysis(source.id(), source.owner(), status, source.reviewStatus(),
                source.reconciliationStatus(), source.supplierName(), source.supplierKey(),
                source.referenceType(), source.referenceNumber(), source.invoiceNumber(), source.currency(),
                source.referenceTotal(), source.invoicedTotal(), source.difference(), source.priceTolerance(),
                source.retryable(), source.failureCode(), source.failureUserMessage(), source.version(),
                source.completedAt(), source.expiresAt(), source.createdAt(), source.updatedAt());
    }
}
