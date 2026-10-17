package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.service.GetExtractionService;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.exception.AnalysisReportNotAllowedException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportConflictException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportGenerationException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportInputNotReadyException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.model.GenerateReportResult;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportPersistenceResult;
import io.github.guillermodubon.invoward.reporting.application.model.PresignedReportDownload;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.application.port.ReportArtifactGenerator;
import io.github.guillermodubon.invoward.reporting.application.port.ReportStorage;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class GenerateReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final LocalDate INVOICE_DATE = LocalDate.parse("2026-10-01");
    private static final LocalDate REFERENCE_DATE = LocalDate.parse("2026-09-28");
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

    @TempDir
    Path temporaryDirectory;

    private final UUID analysisId = UUID.randomUUID();
    private final AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
    private final GetAnalysisService analysisService = mock(GetAnalysisService.class);
    private final GeneratedReportRepository generatedReportRepository = mock(GeneratedReportRepository.class);
    private final GetReconciliationService reconciliationService = mock(GetReconciliationService.class);
    private final GetExtractionService extractionService = mock(GetExtractionService.class);
    private final ReportArtifactGenerator artifactGenerator = mock(ReportArtifactGenerator.class);
    private final ReportStorage reportStorage = mock(ReportStorage.class);
    private final PersistGeneratedReportTransaction persistenceTransaction =
            mock(PersistGeneratedReportTransaction.class);
    private final ReportStorageKeyGenerator storageKeyGenerator = new ReportStorageKeyGenerator();
    private final ReportDownloadPolicy downloadPolicy = new ReportDownloadPolicy();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private Analysis analysis;
    private Path artifactPath;
    private GeneratedReportArtifact artifact;
    private GenerateReportService service;

    @BeforeEach
    void setUp() throws Exception {
        analysis = reportReadyAnalysis(analysisId, owner);
        artifactPath = temporaryDirectory.resolve("validated-report.pdf");
        Files.write(artifactPath, new byte[] {0x25, 0x50, 0x44, 0x46, 0x2d, 0x31});
        artifact = GeneratedReportArtifact.validated(artifactPath, 6, "a".repeat(64));
        service = new GenerateReportService(
                analysisService, generatedReportRepository, reconciliationService, extractionService,
                artifactGenerator, reportStorage, persistenceTransaction, storageKeyGenerator, downloadPolicy,
                DOWNLOAD_TTL, clock);

        when(analysisService.get(analysisId, owner)).thenReturn(analysis);
        when(generatedReportRepository.findByAnalysisId(analysisId)).thenReturn(Optional.empty());
        when(reconciliationService.get(analysisId, owner)).thenReturn(reconciliation(analysis));
        when(extractionService.get(analysisId, owner)).thenReturn(confirmedExtractionReview());
        when(artifactGenerator.generate(any())).thenReturn(artifact);
        when(persistenceTransaction.persist(any(), any())).thenAnswer(call ->
                new GeneratedReportPersistenceResult(call.getArgument(1), true));
        when(reportStorage.createPresignedDownload(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(call -> new PresignedReportDownload(
                        URI.create("https://private-storage.invalid/signed"), NOW.plus(call.getArgument(2))));
    }

    @Test
    void rendersStoresPersistsAndPresignsOneImmutableSnapshotAfterOwnerAuthorization() {
        GenerateReportResult result = service.generate(analysisId, owner);

        assertTrue(result.createdByThisCall());
        assertNotNull(result.report().id());
        assertEquals("application/pdf", result.report().contentType());
        assertEquals(6, result.report().sizeBytes());
        assertEquals(NOW, result.report().generatedAt());
        assertTrue(result.report().downloadUrl().isAbsolute());
        assertFalse(Files.exists(artifactPath));

        ArgumentCaptor<ReportSnapshot> snapshotCaptor = ArgumentCaptor.forClass(ReportSnapshot.class);
        verify(artifactGenerator).generate(snapshotCaptor.capture());
        ReportSnapshot snapshot = snapshotCaptor.getValue();
        assertEquals(INVOICE_DATE, snapshot.invoiceDate());
        assertEquals(REFERENCE_DATE, snapshot.referenceDate());
        assertEquals(NOW, snapshot.generatedAt());

        ArgumentCaptor<GeneratedReport> reportCaptor = ArgumentCaptor.forClass(GeneratedReport.class);
        verify(persistenceTransaction).persist(org.mockito.ArgumentMatchers.eq(owner), reportCaptor.capture());
        GeneratedReport candidate = reportCaptor.getValue();
        assertEquals(analysisId, candidate.analysisId());
        assertEquals("application/pdf", candidate.contentType());
        assertEquals(6, candidate.sizeBytes());
        assertEquals("a".repeat(64), candidate.sha256());
        assertTrue(candidate.storageKey().matches("users/" + ((RegisteredUserOwner) owner).userId()
                + "/analyses/" + analysisId + "/reports/[0-9a-f-]{36}\\.pdf"));

        var order = inOrder(analysisService, generatedReportRepository, reconciliationService,
                extractionService, artifactGenerator, reportStorage, persistenceTransaction);
        order.verify(analysisService).get(analysisId, owner);
        order.verify(generatedReportRepository).findByAnalysisId(analysisId);
        order.verify(reconciliationService).get(analysisId, owner);
        order.verify(extractionService).get(analysisId, owner);
        order.verify(artifactGenerator).generate(any());
        order.verify(reportStorage).store(candidate.storageKey(), artifact);
        order.verify(persistenceTransaction).persist(owner, candidate);
        order.verify(reportStorage).createPresignedDownload(
                candidate.storageKey(), "invoward-report-" + analysisId + ".pdf", DOWNLOAD_TTL);
    }

    @Test
    void existingReportIsIdempotentAndSkipsReconciliationRenderingStorageAndPersistence() {
        GeneratedReport existing = report(analysisId, "existing-report.pdf", null, NOW.minusSeconds(20));
        when(generatedReportRepository.findByAnalysisId(analysisId)).thenReturn(Optional.of(existing));

        GenerateReportResult result = service.generate(analysisId, owner);

        assertFalse(result.createdByThisCall());
        assertEquals(existing.id(), result.report().id());
        verifyNoInteractions(reconciliationService, extractionService, artifactGenerator,
                persistenceTransaction);
        verify(reportStorage, never()).store(anyString(), any());
        verify(reportStorage, never()).delete(anyString());
        verify(reportStorage).createPresignedDownload(
                existing.storageKey(), "invoward-report-" + analysisId + ".pdf", DOWNLOAD_TTL);
        assertTrue(Files.exists(artifactPath));
    }

    @Test
    void ownerAuthorizationPrecedesReportLookupAndAllOtherWork() {
        when(analysisService.get(analysisId, owner)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class, () -> service.generate(analysisId, owner));

        verifyNoInteractions(generatedReportRepository, reconciliationService, extractionService,
                artifactGenerator, reportStorage, persistenceTransaction);
    }

    @Test
    void rejectsWrongWorkflowStateBeforeLoadingReconciliationOrRendering() {
        when(analysisService.get(analysisId, owner)).thenReturn(Analysis.create(
                analysisId, owner, PriceTolerance.exactMatch(), NOW.minusSeconds(20)));

        assertThrows(AnalysisReportNotAllowedException.class, () -> service.generate(analysisId, owner));

        verifyNoInteractions(reconciliationService, extractionService, artifactGenerator,
                reportStorage, persistenceTransaction);
    }

    @Test
    void completedAnalysisWithoutExistingReportIsAConflictBeforeRendering() {
        when(analysisService.get(analysisId, owner)).thenReturn(analysis.completeReportGeneration(NOW));

        assertThrows(ReportConflictException.class, () -> service.generate(analysisId, owner));

        verifyNoInteractions(reconciliationService, extractionService, artifactGenerator,
                reportStorage, persistenceTransaction);
    }

    @Test
    void missingPersistedReconciliationIsReportedAsInputNotReady() {
        when(reconciliationService.get(analysisId, owner)).thenThrow(new ReconciliationNotFoundException());

        assertThrows(ReportInputNotReadyException.class, () -> service.generate(analysisId, owner));

        verifyNoInteractions(extractionService, artifactGenerator, reportStorage, persistenceTransaction);
    }

    @Test
    void extractionReadFailureIsReportedAsInputNotReadyWithoutRendering() {
        when(extractionService.get(analysisId, owner)).thenThrow(new ExtractionNotFoundException());

        assertThrows(ReportInputNotReadyException.class, () -> service.generate(analysisId, owner));

        verifyNoInteractions(artifactGenerator, reportStorage, persistenceTransaction);
    }

    @Test
    void rendererFailureDoesNotStoreOrPersistAndCleansAnyArtifactItCreated() {
        when(artifactGenerator.generate(any())).thenThrow(new ReportGenerationException(new IllegalStateException()));

        assertThrows(ReportGenerationException.class, () -> service.generate(analysisId, owner));

        verify(reportStorage, never()).store(anyString(), any());
        verifyNoInteractions(persistenceTransaction);
        assertTrue(Files.exists(artifactPath));
    }

    @Test
    void storageFailureDoesNotCallPersistenceAndClosesTheValidatedTemporaryArtifact() {
        doThrow(new ReportStorageException(ReportStorageException.Failure.UNAVAILABLE))
                .when(reportStorage).store(anyString(), any());

        assertThrows(ReportStorageException.class, () -> service.generate(analysisId, owner));

        verifyNoInteractions(persistenceTransaction);
        verify(reportStorage, never()).delete(anyString());
        assertFalse(Files.exists(artifactPath));
    }

    @Test
    void persistenceFailureDeletesStoredObjectAndPreservesTheOriginalFailureIfCleanupAlsoFails() {
        ReportConflictException original = new ReportConflictException();
        doThrow(original).when(persistenceTransaction).persist(any(), any());
        doThrow(new ReportStorageException(ReportStorageException.Failure.UNAVAILABLE))
                .when(reportStorage).delete(anyString());

        ReportConflictException thrown = assertThrows(
                ReportConflictException.class, () -> service.generate(analysisId, owner));

        assertSame(original, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertFalse(thrown.getMessage().contains("reports/"));
        assertFalse(thrown.getSuppressed()[0].toString().contains("storageKey"));
        verify(reportStorage).delete(anyString());
        verify(reportStorage, never()).createPresignedDownload(anyString(), anyString(), any(Duration.class));
        assertFalse(Files.exists(artifactPath));
    }

    @Test
    void concurrentLoserDeletesItsObjectAndReturnsThePersistedWinner() {
        GeneratedReport winner = report(analysisId, "winner.pdf", null, NOW.minusSeconds(30));
        doReturn(new GeneratedReportPersistenceResult(winner, false))
                .when(persistenceTransaction).persist(any(), any());

        GenerateReportResult result = service.generate(analysisId, owner);

        assertFalse(result.createdByThisCall());
        assertEquals(winner.id(), result.report().id());
        verify(reportStorage).store(anyString(), any());
        verify(reportStorage).delete(anyString());
        verify(reportStorage).createPresignedDownload(
                winner.storageKey(), "invoward-report-" + analysisId + ".pdf", DOWNLOAD_TTL);
        assertFalse(Files.exists(artifactPath));
    }

    @Test
    void presignFailureAfterCommitDoesNotDeleteThePersistedReportObject() {
        doThrow(new ReportStorageException(ReportStorageException.Failure.UNAVAILABLE))
                .when(reportStorage).createPresignedDownload(anyString(), anyString(), any(Duration.class));

        assertThrows(ReportStorageException.class, () -> service.generate(analysisId, owner));

        verify(persistenceTransaction).persist(any(), any());
        verify(reportStorage, never()).delete(anyString());
        assertFalse(Files.exists(artifactPath));
    }

    private static Analysis reportReadyAnalysis(UUID analysisId, AnalysisOwner owner) {
        Instant start = NOW.minusSeconds(30);
        return Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), start)
                .transitionToUploading(start.plusSeconds(1))
                .markClassifying(start.plusSeconds(2))
                .awaitExtractionConfirmation(start.plusSeconds(3))
                .confirmExtraction("QUOTE", "REF-1", "INV-1", "Supplier", "USD",
                        new BigDecimal("100.0000"), new BigDecimal("100.0000"), start.plusSeconds(4))
                .beginReconciliation(start.plusSeconds(5))
                .completeReconciliation(AnalysisReconciliationStatus.MATCHED, BigDecimal.ZERO, start.plusSeconds(6));
    }

    private static ReconciliationResult reconciliation(Analysis analysis) {
        return new ReconciliationResult(
                analysis.id(), analysis.status(), analysis.reconciliationStatus(), analysis.priceTolerance(),
                analysis.referenceTotal(), analysis.invoicedTotal(), analysis.difference(),
                DuplicateInvoiceWarning.none(), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of());
    }

    private static ExtractionReview confirmedExtractionReview() {
        return new ExtractionReview(
                reviewedDocument(DocumentRole.REFERENCE, DocumentType.QUOTE, REFERENCE_DATE),
                reviewedDocument(DocumentRole.INVOICE, DocumentType.INVOICE, INVOICE_DATE));
    }

    private static ExtractionReview.ReviewedDocument reviewedDocument(
            DocumentRole role, DocumentType type, LocalDate documentDate) {
        UUID documentId = UUID.randomUUID();
        ExtractedDocument extraction = new ExtractedDocument(
                ExtractionStatus.CONFIRMED, ExtractionSource.AI, NOW.minusSeconds(10),
                "Supplier", role == DocumentRole.INVOICE ? "INV-1" : "REF-1", documentDate,
                "USD", null, null, null, null, List.of());
        return new ExtractionReview.ReviewedDocument(
                UUID.randomUUID(), documentId, role, type, 0, List.of(), extraction);
    }

    private static GeneratedReport report(UUID analysisId, String name, Instant expiresAt, Instant generatedAt) {
        return new GeneratedReport(UUID.randomUUID(), analysisId, "reports/" + name,
                "application/pdf", 100, "b".repeat(64), expiresAt, generatedAt);
    }
}
