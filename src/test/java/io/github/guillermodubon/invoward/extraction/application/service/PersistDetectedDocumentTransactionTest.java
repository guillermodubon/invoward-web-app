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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistDetectedDocumentTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void concurrentPersistedDetectionWinsInsteadOfBeingOverwritten() {
        UUID analysisId = UUID.randomUUID();
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        Analysis analysis = Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), NOW)
                .transitionToUploading(NOW);
        Document original = Document.createUploaded(
                UUID.randomUUID(), analysisId, DocumentRole.REFERENCE, "reference.pdf", "application/pdf",
                10, 1, "b".repeat(64), "private-key", null, NOW);
        Document concurrentResult = original.withDetectedType(DocumentType.PURCHASE_ORDER);
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        when(analyses.findOwnedByIdForUpdate(analysisId, owner, NOW)).thenReturn(Optional.of(analysis));
        when(documents.findByIdAndAnalysisId(original.id(), analysisId)).thenReturn(Optional.of(concurrentResult));
        PersistDetectedDocumentTransaction transaction = new PersistDetectedDocumentTransaction(
                analyses, documents, Clock.fixed(NOW, ZoneOffset.UTC));

        Document result = transaction.persist(owner, original, DocumentType.QUOTE);

        assertEquals(DocumentType.PURCHASE_ORDER, result.detectedType());
        verify(documents, never()).updateTypes(original.withDetectedType(DocumentType.QUOTE));
    }
}
