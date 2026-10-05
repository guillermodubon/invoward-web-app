package io.github.guillermodubon.invoward.extraction.application.service;

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
import io.github.guillermodubon.invoward.extraction.application.exception.ConfirmedDocumentTypeInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmExtractionTypesTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());

    @Mock private AnalysisRepository analysisRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private ExtractedDocumentRepository extractedDocumentRepository;

    private ConfirmExtractionTypesTransaction transaction;
    private Document reference;
    private Document invoice;

    @BeforeEach
    void setUp() {
        transaction = new ConfirmExtractionTypesTransaction(
                analysisRepository,
                documentRepository,
                extractedDocumentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        Analysis classifying = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW)
                .transitionToUploading(NOW.plusSeconds(1))
                .markClassifying(NOW.plusSeconds(2));
        reference = document(DocumentRole.REFERENCE, DocumentType.INVOICE);
        invoice = document(DocumentRole.INVOICE, DocumentType.QUOTE);

        lenient().when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(classifying));
        lenient().when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(invoice, reference));
        lenient().when(extractedDocumentRepository.findByDocumentId(reference.id())).thenReturn(Optional.empty());
        lenient().when(extractedDocumentRepository.findByDocumentId(invoice.id())).thenReturn(Optional.empty());
        lenient().when(documentRepository.updateTypes(any(Document.class))).thenAnswer(invocation ->
                Optional.of(invocation.getArgument(0)));
    }

    @Test
    void compatibleUserSelectionsArePersistedForBothDocuments() {
        var pair = transaction.confirm(ANALYSIS_ID, OWNER,
                new ExtractionRequestTypes(DocumentType.PURCHASE_ORDER, DocumentType.UNKNOWN));

        assertEquals(DocumentType.PURCHASE_ORDER, pair.reference().confirmedType());
        assertEquals(DocumentType.UNKNOWN, pair.invoice().confirmedType());
        verify(documentRepository).updateTypes(reference.withConfirmedType(DocumentType.PURCHASE_ORDER));
        verify(documentRepository).updateTypes(invoice.withConfirmedType(DocumentType.UNKNOWN));
    }

    @Test
    void incompatibleInvoiceTypeFailsBeforeEitherTypeIsPersisted() {
        assertThrows(ConfirmedDocumentTypeInvalidException.class,
                () -> transaction.confirm(ANALYSIS_ID, OWNER,
                        new ExtractionRequestTypes(DocumentType.QUOTE, DocumentType.ESTIMATE)));

        verify(documentRepository, never()).updateTypes(any(Document.class));
    }

    @Test
    void incompatibleReferenceTypeFailsBeforeEitherTypeIsPersisted() {
        assertThrows(ConfirmedDocumentTypeInvalidException.class,
                () -> transaction.confirm(ANALYSIS_ID, OWNER,
                        new ExtractionRequestTypes(DocumentType.INVOICE, DocumentType.INVOICE)));

        verify(documentRepository, never()).updateTypes(any(Document.class));
    }

    @Test
    void extractionMustNotAlreadyExistBeforeChangingConfirmedTypes() {
        when(extractedDocumentRepository.findByDocumentId(reference.id()))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(
                        io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction.class)));

        assertThrows(io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException.class,
                () -> transaction.confirm(ANALYSIS_ID, OWNER,
                        new ExtractionRequestTypes(DocumentType.QUOTE, DocumentType.INVOICE)));

        verify(documentRepository, never()).updateTypes(any(Document.class));
    }

    @Test
    void analysisMustStillBeClassifyingUnderTheOwnerScopedLock() {
        Analysis uploading = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW)
                .transitionToUploading(NOW.plusSeconds(1));
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(uploading));

        assertThrows(DocumentClassificationConflictException.class,
                () -> transaction.confirm(ANALYSIS_ID, OWNER,
                        new ExtractionRequestTypes(DocumentType.QUOTE, DocumentType.INVOICE)));

        verify(documentRepository, never()).updateTypes(any(Document.class));
    }

    private static Document document(DocumentRole role, DocumentType detectedType) {
        return Document.createUploaded(UUID.randomUUID(), ANALYSIS_ID, role,
                role + ".pdf", "application/pdf", 100, 1, "a".repeat(64),
                "private-key-" + role, null, NOW).withDetectedType(detectedType);
    }
}
