package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconciliationLinePositionReaderTest {

    private static final Instant NOW = Instant.parse("2026-10-09T15:00:00Z");
    private static final UUID ANALYSIS_ID = uuid(1);
    private static final UUID REFERENCE_DOCUMENT_ID = uuid(2);
    private static final UUID INVOICE_DOCUMENT_ID = uuid(3);
    private static final UUID REFERENCE_LINE_ZERO_ID = uuid(4);
    private static final UUID REFERENCE_LINE_ONE_ID = uuid(5);
    private static final UUID INVOICE_LINE_ZERO_ID = uuid(6);

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final ExtractedDocumentRepository extractedDocumentRepository = mock(ExtractedDocumentRepository.class);
    private final ReconciliationLinePositionReader reader = new ReconciliationLinePositionReader(
            documentRepository, extractedDocumentRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    private Analysis analysis;
    private Document reference;
    private Document invoice;

    @BeforeEach
    void setUp() {
        analysis = Analysis.create(ANALYSIS_ID, new RegisteredUserOwner(uuid(20)), PriceTolerance.exactMatch(), NOW);
        reference = document(REFERENCE_DOCUMENT_ID, DocumentRole.REFERENCE, DocumentType.QUOTE);
        invoice = document(INVOICE_DOCUMENT_ID, DocumentRole.INVOICE, DocumentType.INVOICE);
    }

    @Test
    void returnsOnlyConfirmedSourceLinePositionsForBothDocuments() {
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(invoice, reference));
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID))
                .thenReturn(Optional.of(extraction(reference, List.of(REFERENCE_LINE_ZERO_ID, REFERENCE_LINE_ONE_ID))));
        when(extractedDocumentRepository.findByDocumentId(INVOICE_DOCUMENT_ID))
                .thenReturn(Optional.of(extraction(invoice, List.of(INVOICE_LINE_ZERO_ID))));

        assertEquals(Map.of(
                REFERENCE_LINE_ZERO_ID, 0,
                REFERENCE_LINE_ONE_ID, 1,
                INVOICE_LINE_ZERO_ID, 0), reader.read(analysis));
        verify(documentRepository).findByAnalysisId(ANALYSIS_ID);
        verify(extractedDocumentRepository).findByDocumentId(REFERENCE_DOCUMENT_ID);
        verify(extractedDocumentRepository).findByDocumentId(INVOICE_DOCUMENT_ID);
    }

    @Test
    void rejectsMissingConfirmedSourceWithoutInventingPositions() {
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(reference, invoice));
        when(extractedDocumentRepository.findByDocumentId(REFERENCE_DOCUMENT_ID)).thenReturn(Optional.empty());

        assertThrows(ReconciliationConflictException.class, () -> reader.read(analysis));

        verify(extractedDocumentRepository).findByDocumentId(REFERENCE_DOCUMENT_ID);
        verifyNoInteractionsWithInvoiceExtraction();
    }

    private void verifyNoInteractionsWithInvoiceExtraction() {
        org.mockito.Mockito.verify(extractedDocumentRepository,
                org.mockito.Mockito.never()).findByDocumentId(INVOICE_DOCUMENT_ID);
    }

    private PersistedExtraction extraction(Document document, List<UUID> ids) {
        List<ExtractedLineItem> lines = ids.stream()
                .map(id -> new ExtractedLineItem(
                        ids.indexOf(id), "SKU-" + ids.indexOf(id), "Item " + ids.indexOf(id),
                        BigDecimal.ONE, "each", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.TEN, 1, "Item " + ids.indexOf(id), null))
                .toList();
        ExtractedDocument confirmed = ExtractedDocument.draft(
                ExtractionSource.CACHE, "Vendor", null, null, "USD",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, lines).confirm(NOW);
        return new PersistedExtraction(uuid(30), document.id(), confirmed, ids,
                "document-extraction-v1", null, 1, 0, NOW, NOW, NOW);
    }

    private Document document(UUID id, DocumentRole role, DocumentType type) {
        return Document.createUploaded(id, ANALYSIS_ID, role, role + ".pdf", "application/pdf", 100,
                        1, "a".repeat(64), "private/" + id, null, NOW.minusSeconds(1))
                .withDetectedType(type)
                .withConfirmedType(type);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
