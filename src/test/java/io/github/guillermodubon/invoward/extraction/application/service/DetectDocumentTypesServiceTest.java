package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentClassification;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import io.github.guillermodubon.invoward.extraction.application.model.DetectedDocumentType;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectDocumentTypesServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());

    @Mock private GetAnalysisService analysisService;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentIntelligenceFileMaterializer materializer;
    @Mock private DocumentIntelligence documentIntelligence;
    @Mock private PersistDetectedDocumentTransaction persistTransaction;
    @Mock private CompleteDocumentClassificationTransaction completeTransaction;

    private DetectDocumentTypesService service;
    private Document reference;
    private Document invoice;
    private final Map<UUID, DocumentType> configuredClassifications = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        service = new DetectDocumentTypesService(
                analysisService, documentRepository, materializer, documentIntelligence,
                persistTransaction, completeTransaction, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(documentIntelligence.classify(any())).thenAnswer(invocation -> {
            DocumentIntelligenceInput input = invocation.getArgument(0);
            if (input == null) {
                return null;
            }
            DocumentType type = configuredClassifications.get(input.documentId());
            return type == null ? null : new DocumentClassification(type);
        });
        reference = document(DocumentRole.REFERENCE, null);
        invoice = document(DocumentRole.INVOICE, null);
    }

    @Test
    void classifiesBothMissingTypesInDeterministicRoleOrderAndCompletes() {
        prepareUploading(List.of(invoice, reference));
        classify(reference, DocumentType.QUOTE);
        classify(invoice, DocumentType.INVOICE);
        when(completeTransaction.complete(OWNER, ANALYSIS_ID)).thenReturn(results(
                reference.withDetectedType(DocumentType.QUOTE), invoice.withDetectedType(DocumentType.INVOICE)));

        List<DetectedDocumentType> result = service.detect(ANALYSIS_ID, OWNER);

        assertEquals(List.of(DocumentRole.REFERENCE, DocumentRole.INVOICE), result.stream()
                .map(DetectedDocumentType::role).toList());
        verify(documentIntelligence, org.mockito.Mockito.times(2)).classify(any(DocumentIntelligenceInput.class));
        verify(persistTransaction).persist(OWNER, reference, DocumentType.QUOTE);
        verify(persistTransaction).persist(OWNER, invoice, DocumentType.INVOICE);
        verify(completeTransaction).complete(OWNER, ANALYSIS_ID);
    }

    @Test
    void classifiesOnlyInvoiceWhenReferenceSuggestionAlreadyExists() {
        reference = reference.withDetectedType(DocumentType.QUOTE);
        prepareUploading(List.of(reference, invoice));
        classify(invoice, DocumentType.INVOICE);
        when(completeTransaction.complete(OWNER, ANALYSIS_ID)).thenReturn(results(
                reference, invoice.withDetectedType(DocumentType.INVOICE)));

        service.detect(ANALYSIS_ID, OWNER);

        verify(documentIntelligence).classify(any(DocumentIntelligenceInput.class));
        verify(persistTransaction, never()).persist(eq(OWNER), eq(reference), any());
        verify(persistTransaction).persist(OWNER, invoice, DocumentType.INVOICE);
    }

    @Test
    void classifiesOnlyReferenceWhenInvoiceSuggestionAlreadyExists() {
        invoice = invoice.withDetectedType(DocumentType.INVOICE);
        prepareUploading(List.of(reference, invoice));
        classify(reference, DocumentType.ESTIMATE);
        when(completeTransaction.complete(OWNER, ANALYSIS_ID)).thenReturn(results(
                reference.withDetectedType(DocumentType.ESTIMATE), invoice));

        service.detect(ANALYSIS_ID, OWNER);

        verify(documentIntelligence).classify(any(DocumentIntelligenceInput.class));
        verify(persistTransaction).persist(OWNER, reference, DocumentType.ESTIMATE);
        verify(persistTransaction, never()).persist(eq(OWNER), eq(invoice), any());
    }

    @Test
    void alreadyClassifiedAnalysisReturnsPersistedValuesWithoutProviderCalls() {
        reference = reference.withDetectedType(DocumentType.UNKNOWN);
        invoice = invoice.withDetectedType(DocumentType.QUOTE);
        prepareClassifying(List.of(invoice, reference));

        List<DetectedDocumentType> result = service.detect(ANALYSIS_ID, OWNER);

        assertEquals(List.of(DocumentType.UNKNOWN, DocumentType.QUOTE), result.stream()
                .map(DetectedDocumentType::detectedType).toList());
        verifyNoInteractions(materializer, documentIntelligence, persistTransaction, completeTransaction);
    }

    @Test
    void unknownAndRoleIncompatibleSuggestionsAreAcceptedAsSuggestions() {
        classify(reference, DocumentType.INVOICE);
        classify(invoice, DocumentType.UNKNOWN);
        prepareUploading(List.of(reference, invoice));
        when(completeTransaction.complete(OWNER, ANALYSIS_ID)).thenReturn(results(
                reference.withDetectedType(DocumentType.INVOICE), invoice.withDetectedType(DocumentType.UNKNOWN)));

        List<DetectedDocumentType> result = service.detect(ANALYSIS_ID, OWNER);

        assertEquals(DocumentType.INVOICE, result.getFirst().detectedType());
        verify(persistTransaction).persist(OWNER, reference, DocumentType.INVOICE);
    }

    @Test
    void missingEitherRequiredRoleFailsBeforeStorageOrAi() {
        prepareUploading(List.of(reference));

        assertThrows(DocumentsRequiredException.class, () -> service.detect(ANALYSIS_ID, OWNER));

        verifyNoInteractions(materializer, documentIntelligence, persistTransaction, completeTransaction);
    }

    @Test
    void expiredDocumentDoesNotReachStorageOrAi() {
        Instant createdAt = NOW.minusSeconds(10);
        Document expiredReference = Document.createUploaded(
                UUID.randomUUID(), ANALYSIS_ID, DocumentRole.REFERENCE, "reference.pdf", "application/pdf",
                10, 1, "a".repeat(64), "private-key", NOW.minusSeconds(1), createdAt);
        prepareUploading(List.of(expiredReference, invoice));

        assertThrows(DocumentClassificationConflictException.class,
                () -> service.detect(ANALYSIS_ID, OWNER));

        verifyNoInteractions(materializer, documentIntelligence, persistTransaction, completeTransaction);
    }

    @Test
    void wrongOwnerIsRejectedBeforeDocumentsStorageOrAi() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class, () -> service.detect(ANALYSIS_ID, OWNER));

        verifyNoInteractions(documentRepository, materializer, documentIntelligence,
                persistTransaction, completeTransaction);
    }

    @Test
    void laterAnalysisStateDoesNotCallProviderOrStorage() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(analysis(AnalysisStatus.MATCHING));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));

        assertThrows(DocumentClassificationConflictException.class, () -> service.detect(ANALYSIS_ID, OWNER));

        verifyNoInteractions(materializer, documentIntelligence, persistTransaction, completeTransaction);
    }

    @Test
    void storageFailurePreventsAiPersistenceAndTransition() {
        prepareUploading(List.of(reference, invoice));
        when(materializer.withMaterializedFile(eq(reference), anyOperation()))
                .thenThrow(new DocumentStorageException(DocumentStorageException.Failure.UNAVAILABLE));

        assertThrows(DocumentStorageException.class, () -> service.detect(ANALYSIS_ID, OWNER));

        verifyNoInteractions(documentIntelligence, persistTransaction, completeTransaction);
    }

    @Test
    void unavailableProviderDoesNotPersistOrAdvanceWorkflow() {
        prepareUploading(List.of(reference, invoice));
        applyOperation(reference);
        when(documentIntelligence.classify(any())).thenThrow(
                new DocumentIntelligenceException(DocumentIntelligenceException.Failure.UNAVAILABLE));

        assertThrows(DocumentIntelligenceException.class, () -> service.detect(ANALYSIS_ID, OWNER));

        verifyNoInteractions(persistTransaction, completeTransaction);
    }

    @Test
    void partialProviderFailurePreservesFirstDetectionAndRetryOnlyClassifiesMissingDocument() {
        prepareUploading(List.of(reference, invoice));
        classify(reference, DocumentType.QUOTE);
        applyOperation(invoice);
        when(documentIntelligence.classify(any()))
                .thenReturn(new DocumentClassification(DocumentType.QUOTE))
                .thenThrow(new DocumentIntelligenceException(DocumentIntelligenceException.Failure.UNAVAILABLE));

        assertThrows(DocumentIntelligenceException.class, () -> service.detect(ANALYSIS_ID, OWNER));

        verify(persistTransaction).persist(OWNER, reference, DocumentType.QUOTE);
        verify(persistTransaction, never()).persist(OWNER, invoice, DocumentType.QUOTE);
        verifyNoInteractions(completeTransaction);
    }

    @Test
    void nullClassificationIsRejectedWithoutPersistence() {
        prepareUploading(List.of(reference, invoice));
        applyOperation(reference);
        when(documentIntelligence.classify(any())).thenReturn(null);

        assertThrows(DocumentClassificationInvalidException.class,
                () -> service.detect(ANALYSIS_ID, OWNER));

        verifyNoInteractions(persistTransaction, completeTransaction);
    }

    private void prepareUploading(List<Document> documents) {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(analysis(AnalysisStatus.UPLOADING));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(documents);
        for (Document document : documents) {
            if (document.detectedType() != null) {
                continue;
            }
            lenient().when(documentRepository.findByIdAndAnalysisId(document.id(), ANALYSIS_ID))
                    .thenReturn(Optional.of(document));
        }
    }

    private void prepareClassifying(List<Document> documents) {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenReturn(analysis(AnalysisStatus.CLASSIFYING));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(documents);
    }

    private void classify(Document document, DocumentType type) {
        applyOperation(document);
        configuredClassifications.put(document.id(), type);
        when(persistTransaction.persist(OWNER, document, type)).thenReturn(document.withDetectedType(type));
    }

    private void applyOperation(Document document) {
        when(materializer.withMaterializedFile(eq(document), anyOperation()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Function<DocumentIntelligenceInput, DocumentClassification> operation =
                            invocation.getArgument(1);
                    return operation.apply(new DocumentIntelligenceInput(
                            document.id(), document.role(), document.contentType(), document.pageCount(),
                            java.nio.file.Path.of("synthetic-test-file")));
                });
    }

    @SuppressWarnings("unchecked")
    private static Function<DocumentIntelligenceInput, DocumentClassification> anyOperation() {
        return (Function<DocumentIntelligenceInput, DocumentClassification>) any(Function.class);
    }

    private static Analysis analysis(AnalysisStatus status) {
        Analysis created = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW);
        return status == AnalysisStatus.CLASSIFYING
                ? created.transitionToUploading(NOW.plusSeconds(1)).markClassifying(NOW.plusSeconds(2))
                : status == AnalysisStatus.UPLOADING
                        ? created.transitionToUploading(NOW.plusSeconds(1))
                        : withStatus(created, status);
    }

    private static Analysis withStatus(Analysis source, AnalysisStatus status) {
        return new Analysis(source.id(), source.owner(), status, source.reviewStatus(),
                source.reconciliationStatus(), source.supplierName(), source.supplierKey(),
                source.referenceType(), source.referenceNumber(), source.invoiceNumber(), source.currency(),
                source.referenceTotal(), source.invoicedTotal(), source.difference(), source.priceTolerance(),
                source.retryable(), source.failureCode(), source.failureUserMessage(), source.version(),
                source.completedAt(), source.expiresAt(), source.createdAt(), source.updatedAt());
    }

    private static Document document(DocumentRole role, DocumentType detectedType) {
        Document created = Document.createUploaded(
                UUID.randomUUID(), ANALYSIS_ID, role, role + ".pdf", "application/pdf",
                10, 1, "a".repeat(64), "server-storage-key-" + role, null, NOW);
        return detectedType == null ? created : created.withDetectedType(detectedType);
    }

    private static List<DetectedDocumentType> results(Document reference, Document invoice) {
        return List.of(
                new DetectedDocumentType(reference.id(), reference.role(), reference.detectedType()),
                new DetectedDocumentType(invoice.id(), invoice.role(), invoice.detectedType()));
    }
}
