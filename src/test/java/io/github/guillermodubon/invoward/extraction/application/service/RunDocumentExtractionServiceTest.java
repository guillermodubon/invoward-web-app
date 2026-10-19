package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDocumentPair;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionPreparation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunDocumentExtractionServiceTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @Mock private PrepareExtractionService preparationService;
    @Mock private PersistExtractionTransaction persistenceTransaction;

    @Test
    void workerPreparesBothDocumentsThenPersistsTheirDraftsAtomically() {
        ExtractionPreparation.Ready ready = ready();
        ExtractionReview review = review();
        when(preparationService.prepareForWorker(ANALYSIS_ID, OWNER)).thenReturn(ready);
        when(persistenceTransaction.persist(ANALYSIS_ID, OWNER, ready)).thenReturn(review);
        RunDocumentExtractionService service = new RunDocumentExtractionService(
                preparationService, persistenceTransaction);

        assertSame(review, service.run(ANALYSIS_ID, OWNER));

        verify(preparationService).prepareForWorker(ANALYSIS_ID, OWNER);
        verify(persistenceTransaction).persist(ANALYSIS_ID, OWNER, ready);
    }

    @Test
    void preparationFailureDoesNotAttemptDraftPersistence() {
        IllegalStateException failure = new IllegalStateException("provider unavailable");
        when(preparationService.prepareForWorker(ANALYSIS_ID, OWNER)).thenThrow(failure);
        RunDocumentExtractionService service = new RunDocumentExtractionService(
                preparationService, persistenceTransaction);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.run(ANALYSIS_ID, OWNER)));

        verifyNoInteractions(persistenceTransaction);
    }

    private static ExtractionPreparation.Ready ready() {
        Document reference = document(DocumentRole.REFERENCE, DocumentType.PURCHASE_ORDER);
        Document invoice = document(DocumentRole.INVOICE, DocumentType.INVOICE);
        return new ExtractionPreparation.Ready(
                new ExtractionDocumentPair(reference, invoice),
                draft(), draft(), "gemini-test-model");
    }

    private static ExtractionReview review() {
        UUID referenceId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        return new ExtractionReview(
                new ExtractionReview.ReviewedDocument(
                        UUID.randomUUID(), referenceId, DocumentRole.REFERENCE,
                        DocumentType.PURCHASE_ORDER, 0, List.of(), draft()),
                new ExtractionReview.ReviewedDocument(
                        UUID.randomUUID(), invoiceId, DocumentRole.INVOICE,
                        DocumentType.INVOICE, 0, List.of(), draft()));
    }

    private static Document document(DocumentRole role, DocumentType type) {
        return Document.createUploaded(UUID.randomUUID(), ANALYSIS_ID, role,
                        role.name().toLowerCase() + ".pdf", "application/pdf", 128, 1,
                        "a".repeat(64), "private/" + UUID.randomUUID(), null, NOW)
                .withDetectedType(type)
                .withConfirmedType(type);
    }

    private static ExtractedDocument draft() {
        return ExtractedDocument.draft(ExtractionSource.CACHE,
                null, null, null, null, null, null, null, null, List.of());
    }
}
