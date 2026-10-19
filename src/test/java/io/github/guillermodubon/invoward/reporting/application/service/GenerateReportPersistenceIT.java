package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.service.GetExtractionService;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.model.GenerateReportResult;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.application.port.ReportArtifactGenerator;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.processing.infrastructure.ProcessingWorker;
import io.github.guillermodubon.invoward.reporting.infrastructure.config.ReportingProperties;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeReportStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises report storage compensation and idempotency around the real PostgreSQL transaction. */
@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
@Import(GenerateReportPersistenceIT.TestStorageConfiguration.class)
class GenerateReportPersistenceIT {

    private static final byte[] PDF_BYTES = "%PDF-1.7\nsynthetic report fixture\n".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private GenerateReportService generateReportService;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private GeneratedReportRepository generatedReportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private ProcessingWorker processingWorker;

    @Autowired
    private FakeReportStorage storage;

    @MockitoBean
    private GetReconciliationService reconciliationService;

    @MockitoBean
    private GetExtractionService extractionService;

    @MockitoBean
    private ReportArtifactGenerator artifactGenerator;

    @MockitoSpyBean
    private AnalysisJobRepository analysisJobRepositorySpy;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearFakeStorage() {
        storage.clear();
    }

    @Test
    void persistsMetadataCompletesWorkflowAndReturnsExistingReportOnRepeatRequest() throws Exception {
        Fixture fixture = reportReadyFixture(false);
        stubInputsAndArtifact(fixture);

        GenerateReportResult first = generateReportService.generate(fixture.analysisId(), fixture.owner());
        GenerateReportResult repeated = generateReportService.generate(fixture.analysisId(), fixture.owner());

        assertTrue(first.createdByThisCall());
        assertFalse(repeated.createdByThisCall());
        assertEquals(first.report().id(), repeated.report().id());
        GeneratedReport persisted = generatedReportRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals("application/pdf", persisted.contentType());
        assertEquals(PDF_BYTES.length, persisted.sizeBytes());
        assertEquals(sha256(PDF_BYTES), persisted.sha256());
        assertEquals(fixture.analysisId(), persisted.analysisId());
        assertTrue(persisted.storageKey().startsWith("users/" + ((RegisteredUserOwner) fixture.owner()).userId()
                + "/analyses/" + fixture.analysisId() + "/reports/"));
        assertEquals(1L, reportCount(fixture.analysisId()));
        assertEquals(PDF_BYTES.length, storage.storedBytes(persisted.storageKey()).orElseThrow().length);
        assertEquals(1, storage.storedObjectCount());
        assertEquals(1, storage.operationCalls(FakeReportStorage.Operation.STORE));
        assertEquals(0, storage.operationCalls(FakeReportStorage.Operation.DELETE));
        assertEquals(2, storage.operationCalls(FakeReportStorage.Operation.PRESIGN));

        Analysis completed = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob completedJob = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals(AnalysisStatus.COMPLETED, completed.status());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, completed.reconciliationStatus());
        assertEquals(persisted.generatedAt(), completed.completedAt());
        assertEquals(AnalysisJobStatus.COMPLETED, completedJob.status());
        assertEquals(AnalysisStatus.COMPLETED, completedJob.currentStage());
        assertEquals(persisted.generatedAt(), completedJob.completedAt());
        verify(artifactGenerator, org.mockito.Mockito.times(1)).generate(any());
    }

    @Test
    void persistsGuestReportWithExactlyTheFixedAnalysisExpiry() throws Exception {
        Fixture fixture = reportReadyFixture(true);
        stubInputsAndArtifact(fixture);

        GenerateReportResult result = generateReportService.generate(fixture.analysisId(), fixture.owner());

        GeneratedReport persisted = generatedReportRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertTrue(result.createdByThisCall());
        assertEquals(fixture.analysis().expiresAt(), persisted.expiresAt());
        assertTrue(persisted.storageKey().startsWith("guest/" + fixture.analysisId() + "/reports/"));
        assertEquals(1, storage.storedObjectCount());
    }

    @Test
    void storageFailureLeavesDatabaseWorkflowUnchanged() throws Exception {
        Fixture fixture = reportReadyFixture(false);
        stubInputsAndArtifact(fixture);
        storage.configureFailure(FakeReportStorage.Operation.STORE, ReportStorageException.Failure.UNAVAILABLE);

        assertThrows(ReportStorageException.class,
                () -> generateReportService.generate(fixture.analysisId(), fixture.owner()));

        assertEquals(0L, reportCount(fixture.analysisId()));
        assertEquals(0, storage.storedObjectCount());
        assertEquals(0, storage.operationCalls(FakeReportStorage.Operation.DELETE));
        assertWorkflowStillRunningReport(fixture);
    }

    @Test
    void workerPersistsRetryableReportStorageFailureForClaimedAttempt() throws Exception {
        Fixture fixture = reportQueuedFixture(false);
        stubInputsAndArtifact(fixture);
        storage.configureFailure(FakeReportStorage.Operation.STORE, ReportStorageException.Failure.UNAVAILABLE);

        processingWorker.process(fixture.analysisId());

        assertEquals(0L, reportCount(fixture.analysisId()));
        assertEquals(0, storage.storedObjectCount());
        Analysis failed = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob failedJob = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals(AnalysisStatus.FAILED, failed.status());
        assertEquals(AnalysisStatus.GENERATING_REPORT, failedJob.currentStage());
        assertEquals(AnalysisJobStatus.FAILED, failedJob.status());
        assertEquals("REPORT_STORAGE_UNAVAILABLE", failed.failureCode());
        assertEquals("REPORT_STORAGE_UNAVAILABLE", failedJob.lastErrorCode());
        assertTrue(failed.retryable());
        assertTrue(failedJob.retryable());
        assertTrue(failed.completedAt() == null);
        assertTrue(failedJob.completedAt() == null);
    }

    @Test
    void databaseFailureDeletesStoredObjectAndRollsBackMetadataAndWorkflow() throws Exception {
        Fixture fixture = reportReadyFixture(false);
        stubInputsAndArtifact(fixture);
        failAfterJobUpdate();

        assertThrows(DataAccessException.class,
                () -> generateReportService.generate(fixture.analysisId(), fixture.owner()));

        assertEquals(0L, reportCount(fixture.analysisId()));
        assertEquals(0, storage.storedObjectCount());
        assertEquals(1, storage.operationCalls(FakeReportStorage.Operation.STORE));
        assertEquals(1, storage.operationCalls(FakeReportStorage.Operation.DELETE));
        assertWorkflowStillRunningReport(fixture);
    }

    @Test
    void compensationFailurePreservesDatabaseFailureAndDoesNotExposeStorageKey() throws Exception {
        Fixture fixture = reportReadyFixture(false);
        stubInputsAndArtifact(fixture);
        storage.configureFailure(FakeReportStorage.Operation.DELETE, ReportStorageException.Failure.UNAVAILABLE);
        failAfterJobUpdate();

        DataAccessException original = assertThrows(DataAccessException.class,
                () -> generateReportService.generate(fixture.analysisId(), fixture.owner()));

        assertEquals(1, original.getSuppressed().length);
        assertFalse(original.getMessage().contains("/reports/"));
        assertEquals(0L, reportCount(fixture.analysisId()));
        assertEquals(1, storage.storedObjectCount());
        assertEquals(1, storage.operationCalls(FakeReportStorage.Operation.DELETE));
        assertWorkflowStillRunningReport(fixture);
    }

    @Test
    void concurrentRequestsPersistOneWinnerDeleteLoserObjectAndReturnWinner() throws Exception {
        Fixture fixture = reportReadyFixture(false);
        stubInputs(fixture);
        CyclicBarrier renderedByBothRequests = new CyclicBarrier(2);
        when(artifactGenerator.generate(any())).thenAnswer(invocation -> {
            GeneratedReportArtifact artifact = createArtifact();
            await(renderedByBothRequests);
            return artifact;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<GenerateReportResult> first = executor.submit(
                    () -> generateReportService.generate(fixture.analysisId(), fixture.owner()));
            Future<GenerateReportResult> second = executor.submit(
                    () -> generateReportService.generate(fixture.analysisId(), fixture.owner()));

            GenerateReportResult firstResult = first.get(30, TimeUnit.SECONDS);
            GenerateReportResult secondResult = second.get(30, TimeUnit.SECONDS);

            assertEquals(firstResult.report().id(), secondResult.report().id());
            assertTrue(firstResult.createdByThisCall() ^ secondResult.createdByThisCall());
            assertEquals(1L, reportCount(fixture.analysisId()));
            GeneratedReport winner = generatedReportRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
            assertEquals(firstResult.report().id(), winner.id());
            assertEquals(2, storage.operationCalls(FakeReportStorage.Operation.STORE));
            assertEquals(1, storage.operationCalls(FakeReportStorage.Operation.DELETE));
            assertEquals(2, storage.operationCalls(FakeReportStorage.Operation.PRESIGN));
            assertEquals(1, storage.storedObjectCount());
            assertTrue(storage.storedBytes(winner.storageKey()).isPresent());
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture reportReadyFixture(boolean guest) {
        return reportReadyFixture(guest, true);
    }

    private Fixture reportQueuedFixture(boolean guest) {
        return reportReadyFixture(guest, false);
    }

    private Fixture reportReadyFixture(boolean guest, boolean startAttempt) {
        Instant start = clock.instant().truncatedTo(ChronoUnit.MICROS).minusSeconds(60);
        AnalysisOwner owner;
        if (guest) {
            UUID guestSessionId = jdbcTemplate.execute(
                    (ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
            Instant expiry = jdbcTemplate.queryForObject(
                    "SELECT expires_at FROM invoward.guest_sessions WHERE id = ?",
                    (result, row) -> result.getTimestamp(1).toInstant(), guestSessionId);
            owner = new GuestSessionOwner(guestSessionId, expiry);
        } else {
            UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
            owner = new RegisteredUserOwner(userId);
        }

        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), start));
        AnalysisJob job = analysisJobRepository.create(
                AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, start));
        analysis = analysisRepository.update(analysis.transitionToUploading(start.plusSeconds(1)));
        job = analysisJobRepository.update(job.awaitMoreUploads(start.plusSeconds(1)));
        analysis = analysisRepository.update(analysis.markClassifying(start.plusSeconds(2)));
        job = analysisJobRepository.update(job.waitForUserAtClassification(start.plusSeconds(2)));
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(start.plusSeconds(3)));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(start.plusSeconds(3)));
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-REPORT", "INV-REPORT", "Report Test Vendor", "USD",
                new BigDecimal("100.0000"), new BigDecimal("115.2500"), start.plusSeconds(4)));
        job = analysisJobRepository.update(job.waitForMatching(start.plusSeconds(4)));
        analysis = analysisRepository.update(analysis.beginReconciliation(start.plusSeconds(5)));
        job = analysisJobRepository.update(job.waitForReconciliation(start.plusSeconds(5)));
        analysis = analysisRepository.update(analysis.completeReconciliation(
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                new BigDecimal("15.2500"), start.plusSeconds(6)));
        job = analysisJobRepository.update(job.queueReportGeneration(start.plusSeconds(6)));
        if (startAttempt) {
            job = analysisJobRepository.update(job.startQueuedAttempt(start.plusSeconds(7), 3));
        }
        return new Fixture(analysisId, owner, analysis, job);
    }

    private void stubInputsAndArtifact(Fixture fixture) throws Exception {
        stubInputs(fixture);
        when(artifactGenerator.generate(any())).thenAnswer(invocation -> createArtifact());
    }

    private void stubInputs(Fixture fixture) {
        Analysis analysis = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        when(reconciliationService.get(analysis.id(), analysis.owner())).thenReturn(
                new ReconciliationResult(
                        analysis.id(), analysis.status(), analysis.reconciliationStatus(), analysis.priceTolerance(),
                        analysis.referenceTotal(), analysis.invoicedTotal(), analysis.difference(),
                        DuplicateInvoiceWarning.none(), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0),
                        List.of(), List.of()));
        when(extractionService.get(analysis.id(), analysis.owner())).thenReturn(confirmedExtractionReview());
    }

    private GeneratedReportArtifact createArtifact() throws Exception {
        Path artifactPath = Files.createTempFile(temporaryDirectory, "report-", ".pdf");
        Files.write(artifactPath, PDF_BYTES);
        return GeneratedReportArtifact.validated(artifactPath, PDF_BYTES.length, sha256(PDF_BYTES));
    }

    private void failAfterJobUpdate() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated job update failure");
        }).when(analysisJobRepositorySpy).update(any(AnalysisJob.class));
    }

    private void assertWorkflowStillRunningReport(Fixture fixture) {
        Analysis current = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob currentJob = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals(AnalysisStatus.GENERATING_REPORT, current.status());
        assertEquals(AnalysisJobStatus.RUNNING, currentJob.status());
        assertEquals(AnalysisStatus.GENERATING_REPORT, currentJob.currentStage());
        assertEquals(fixture.job().startedAt(), currentJob.startedAt());
        assertTrue(current.completedAt() == null);
        assertTrue(currentJob.completedAt() == null);
    }

    private long reportCount(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoward.generated_reports WHERE analysis_id = ?",
                Long.class, analysisId);
    }

    private static ExtractionReview confirmedExtractionReview() {
        LocalDate date = LocalDate.of(2026, 10, 1);
        return new ExtractionReview(
                reviewedDocument(DocumentRole.REFERENCE, DocumentType.QUOTE, "REF-REPORT", date),
                reviewedDocument(DocumentRole.INVOICE, DocumentType.INVOICE, "INV-REPORT", date));
    }

    private static ExtractionReview.ReviewedDocument reviewedDocument(
            DocumentRole role, DocumentType type, String number, LocalDate date) {
        ExtractedDocument extraction = new ExtractedDocument(
                ExtractionStatus.CONFIRMED, ExtractionSource.AI, Instant.parse("2026-10-01T12:00:00Z"),
                "Report Test Vendor", number, date, "USD", null, null, null, null, List.of());
        return new ExtractionReview.ReviewedDocument(
                UUID.randomUUID(), UUID.randomUUID(), role, type, 0, List.of(), extraction);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("concurrent report rendering did not synchronize", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {

        @Bean
        @Primary
        FakeReportStorage fakeReportStorage(Clock clock) {
            return new FakeReportStorage(clock);
        }
    }

    private record Fixture(UUID analysisId, AnalysisOwner owner, Analysis analysis, AnalysisJob job) {
    }
}
