package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.exception.DocumentNotFoundException;
import io.github.guillermodubon.invoward.document.application.model.DocumentDownload;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentReadServicesTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

    private final GetAnalysisService analysisService = mock(GetAnalysisService.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentStorage documentStorage = mock(DocumentStorage.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ListDocumentsService listService;
    private GetDocumentService getService;

    @BeforeEach
    void setUp() {
        listService = new ListDocumentsService(analysisService, documentRepository);
        getService = new GetDocumentService(
                analysisService, documentRepository, documentStorage, clock, DOWNLOAD_TTL);
    }

    @Test
    void listAuthorizesParentThenReturnsMetadataInReferenceInvoiceOrder() {
        Document invoice = document(DocumentRole.INVOICE, null);
        Document reference = document(DocumentRole.REFERENCE, null);
        when(documentRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(invoice, reference));

        List<Document> result = listService.list(ANALYSIS_ID, OWNER);

        assertEquals(List.of(reference, invoice), result);
        InOrder order = inOrder(analysisService, documentRepository);
        order.verify(analysisService).get(ANALYSIS_ID, OWNER);
        order.verify(documentRepository).findByAnalysisId(ANALYSIS_ID);
    }

    @Test
    void listStopsBeforeDocumentQueryWhenParentIsNotOwned() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class, () -> listService.list(ANALYSIS_ID, OWNER));

        verifyNoInteractions(documentRepository, documentStorage);
    }

    @Test
    void detailAuthorizesParentThenUsesAnalysisScopedDocumentLookupAndConfiguredTtl() {
        Document document = document(DocumentRole.REFERENCE, null);
        PresignedDownload signed = new PresignedDownload(
                URI.create("https://storage.example.invalid/signed"), NOW.plus(DOWNLOAD_TTL));
        when(documentRepository.findByIdAndAnalysisId(DOCUMENT_ID, ANALYSIS_ID))
                .thenReturn(Optional.of(document));
        when(documentStorage.createPresignedDownload(
                document.storageKey(), document.contentType(), document.originalFilename(), DOWNLOAD_TTL))
                .thenReturn(signed);

        DocumentDownload result = getService.get(ANALYSIS_ID, DOCUMENT_ID, OWNER);

        assertSame(document, result.document());
        assertSame(signed, result.download());
        InOrder order = inOrder(analysisService, documentRepository, documentStorage);
        order.verify(analysisService).get(ANALYSIS_ID, OWNER);
        order.verify(documentRepository).findByIdAndAnalysisId(DOCUMENT_ID, ANALYSIS_ID);
        order.verify(documentStorage).createPresignedDownload(
                document.storageKey(), document.contentType(), document.originalFilename(), DOWNLOAD_TTL);
    }

    @Test
    void detailCapsGuestDownloadTtlAtFixedDocumentExpiry() {
        Instant expiry = NOW.plusSeconds(90);
        AnalysisOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), expiry);
        Document document = document(DocumentRole.INVOICE, expiry);
        PresignedDownload signed = new PresignedDownload(
                URI.create("https://storage.example.invalid/guest-signed"), expiry.minusSeconds(1));
        when(documentRepository.findByIdAndAnalysisId(DOCUMENT_ID, ANALYSIS_ID))
                .thenReturn(Optional.of(document));
        when(documentStorage.createPresignedDownload(
                document.storageKey(), document.contentType(), document.originalFilename(), Duration.ofSeconds(89)))
                .thenReturn(signed);

        DocumentDownload result = getService.get(ANALYSIS_ID, DOCUMENT_ID, guestOwner);

        assertEquals(expiry.minusSeconds(1), result.download().expiresAt());
        verify(documentStorage).createPresignedDownload(
                document.storageKey(), document.contentType(), document.originalFilename(), Duration.ofSeconds(89));
    }

    @Test
    void detailDoesNotSignDocumentWhichHasExpired() {
        Document expired = document(DocumentRole.REFERENCE, NOW);
        when(documentRepository.findByIdAndAnalysisId(DOCUMENT_ID, ANALYSIS_ID))
                .thenReturn(Optional.of(expired));

        assertThrows(DocumentNotFoundException.class,
                () -> getService.get(ANALYSIS_ID, DOCUMENT_ID, OWNER));

        verifyNoInteractions(documentStorage);
    }

    @Test
    void missingOrWrongParentDocumentUsesSafeNotFoundAndDoesNotSign() {
        when(documentRepository.findByIdAndAnalysisId(DOCUMENT_ID, ANALYSIS_ID))
                .thenReturn(Optional.empty());

        DocumentNotFoundException failure = assertThrows(DocumentNotFoundException.class,
                () -> getService.get(ANALYSIS_ID, DOCUMENT_ID, OWNER));

        assertEquals("Document was not found.", failure.getMessage());
        verifyNoInteractions(documentStorage);
    }

    @Test
    void detailStopsBeforeDocumentLookupWhenParentIsNotOwned() {
        when(analysisService.get(ANALYSIS_ID, OWNER)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class,
                () -> getService.get(ANALYSIS_ID, DOCUMENT_ID, OWNER));

        verifyNoInteractions(documentRepository, documentStorage);
    }

    @Test
    void detailRejectsStorageUrlThatWouldOutliveGuestDocument() {
        Instant expiry = NOW.plusSeconds(90);
        Document document = document(DocumentRole.REFERENCE, expiry);
        when(documentRepository.findByIdAndAnalysisId(DOCUMENT_ID, ANALYSIS_ID))
                .thenReturn(Optional.of(document));
        when(documentStorage.createPresignedDownload(
                document.storageKey(), document.contentType(), document.originalFilename(), Duration.ofSeconds(89)))
                .thenReturn(new PresignedDownload(
                        URI.create("https://storage.example.invalid/overlong"), expiry.plusMillis(1)));

        assertThrows(IllegalStateException.class,
                () -> getService.get(ANALYSIS_ID, DOCUMENT_ID, OWNER));
    }

    private static Document document(DocumentRole role, Instant expiresAt) {
        return new Document(
                DOCUMENT_ID,
                ANALYSIS_ID,
                role,
                role == DocumentRole.REFERENCE ? DocumentType.QUOTE : DocumentType.INVOICE,
                null,
                role == DocumentRole.REFERENCE ? "quote.pdf" : "invoice.pdf",
                "application/pdf",
                1024,
                1,
                "a".repeat(64),
                "opaque/server-generated-key",
                expiresAt,
                NOW.minusSeconds(60));
    }
}
