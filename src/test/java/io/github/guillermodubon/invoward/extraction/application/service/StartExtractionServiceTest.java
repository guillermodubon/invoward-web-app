package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDocumentPair;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionPreparation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StartExtractionServiceTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final ExtractionRequestTypes REQUEST_TYPES = new ExtractionRequestTypes(
            DocumentType.PURCHASE_ORDER, DocumentType.INVOICE);

    private final PrepareExtractionService preparationService = mock(PrepareExtractionService.class);
    private final PersistExtractionTransaction persistenceTransaction = mock(PersistExtractionTransaction.class);
    private final StartExtractionService service = new StartExtractionService(
            preparationService, persistenceTransaction);

    @Test
    void returnsExistingDraftWithoutCallingPersistenceOrRepreparingIt() {
        ExtractionReview existingReview = review();
        when(preparationService.prepare(ANALYSIS_ID, OWNER, REQUEST_TYPES))
                .thenReturn(new ExtractionPreparation.ExistingDraft(existingReview));

        assertSame(existingReview, service.start(ANALYSIS_ID, OWNER, REQUEST_TYPES));

        verify(preparationService).prepare(ANALYSIS_ID, OWNER, REQUEST_TYPES);
        verifyNoInteractions(persistenceTransaction);
    }

    @Test
    void persistsOnlyAReadyPairAndReturnsThePersistedReview() {
        ExtractionPreparation.Ready ready = readyPreparation();
        ExtractionReview persistedReview = review();
        when(preparationService.prepare(ANALYSIS_ID, OWNER, REQUEST_TYPES)).thenReturn(ready);
        when(persistenceTransaction.persist(ANALYSIS_ID, OWNER, ready)).thenReturn(persistedReview);

        assertSame(persistedReview, service.start(ANALYSIS_ID, OWNER, REQUEST_TYPES));

        verify(persistenceTransaction).persist(ANALYSIS_ID, OWNER, ready);
    }

    private static ExtractionPreparation.Ready readyPreparation() {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        Document reference = document(DocumentRole.REFERENCE, DocumentType.PURCHASE_ORDER, now);
        Document invoice = document(DocumentRole.INVOICE, DocumentType.INVOICE, now);
        return new ExtractionPreparation.Ready(
                new ExtractionDocumentPair(reference, invoice),
                ExtractedDocument.draft(ExtractionSource.AI, null, null, null, null,
                        null, null, null, null, List.of()),
                ExtractedDocument.draft(ExtractionSource.CACHE, null, null, null, null,
                        null, null, null, null, List.of()),
                "test-model");
    }

    private static Document document(DocumentRole role, DocumentType type, Instant now) {
        return Document.createUploaded(UUID.randomUUID(), ANALYSIS_ID, role,
                role + ".pdf", "application/pdf", 100, 1, "a".repeat(64),
                "private-key-" + role, null, now)
                .withDetectedType(type)
                .withConfirmedType(type);
    }

    private static ExtractionReview review() {
        UUID referenceId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        return new ExtractionReview(
                new ExtractionReview.ReviewedDocument(UUID.randomUUID(), referenceId,
                        DocumentRole.REFERENCE, DocumentType.PURCHASE_ORDER, 0, List.of(),
                        ExtractedDocument.draft(ExtractionSource.AI, null, null, null, null,
                                null, null, null, null, List.of())),
                new ExtractionReview.ReviewedDocument(UUID.randomUUID(), invoiceId,
                        DocumentRole.INVOICE, DocumentType.INVOICE, 0, List.of(),
                        ExtractedDocument.draft(ExtractionSource.CACHE, null, null, null, null,
                                null, null, null, null, List.of())));
    }
}
