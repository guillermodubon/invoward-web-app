package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputChangedException;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationPersistenceResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationPlan;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyEvidenceRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.ReconciliationLineRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.EvidenceSide;
import io.github.guillermodubon.invoward.reconciliation.domain.ConfirmedEvidenceSource;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingWakePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
@Import(PersistReconciliationTransactionIT.ProcessingTestConfiguration.class)
class PersistReconciliationTransactionIT {

    @Autowired
    private PersistReconciliationTransaction transaction;

    @Autowired
    private ReconcileAnalysisService reconcileAnalysisService;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ExtractedDocumentRepository extractedDocumentRepository;

    @Autowired
    private LineItemMatchRepository lineItemMatchRepository;

    @Autowired
    private ReconciliationLineRepository reconciliationLineRepository;

    @Autowired
    private DiscrepancyRepository discrepancyRepository;

    @Autowired
    private DiscrepancyEvidenceRepository evidenceRepository;

    @MockitoSpyBean
    private AnalysisRepository analysisRepositorySpy;

    @MockitoSpyBean
    private AnalysisJobRepository analysisJobRepositorySpy;

    @MockitoSpyBean
    private ReconciliationLineRepository reconciliationLineRepositorySpy;

    @MockitoSpyBean
    private DiscrepancyRepository discrepancyRepositorySpy;

    @MockitoSpyBean
    private DiscrepancyEvidenceRepository evidenceRepositorySpy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private ProcessingWakeProbe processingWakeProbe;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsMatchedResultAndAdvancesAnalysisAndJobAtomically() throws Exception {
        Fixture fixture = fixture(false);

        ReconciliationPersistenceResult result = transaction.persist(
                fixture.owner(), fixture.input(), fixture.plan(), AnalysisReconciliationStatus.MATCHED);

        assertTrue(result.createdByThisCall());
        assertEquals(AnalysisStatus.GENERATING_REPORT, result.analysis().status());
        assertEquals(AnalysisReconciliationStatus.MATCHED, result.analysis().reconciliationStatus());
        assertEquals(1, result.lines().size());
        assertTrue(result.discrepancies().isEmpty());
        assertWorkflow(fixture.analysisId(), AnalysisStatus.GENERATING_REPORT);
        assertWakeObservedAfterCommit(fixture.analysisId());
        assertEquals(1, reconciliationLineRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(0, discrepancyRepository.countByAnalysisId(fixture.analysisId()));
    }

    @Test
    void persistsReviewRequiredResultAndConfirmedSourceEvidence() throws Exception {
        Fixture fixture = fixture(true);

        ReconciliationPersistenceResult result = transaction.persist(
                fixture.owner(), fixture.input(), fixture.plan(), AnalysisReconciliationStatus.REVIEW_REQUIRED);

        assertTrue(result.createdByThisCall());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, result.analysis().reconciliationStatus());
        assertEquals(ReconciliationLineStatus.DIFFERENT, result.lines().getFirst().status());
        assertEquals(1, result.discrepancies().size());
        assertEquals(2, evidenceRepository.findByDiscrepancyId(result.discrepancies().getFirst().id()).size());
        assertWorkflow(fixture.analysisId(), AnalysisStatus.GENERATING_REPORT);
        assertWakeObservedAfterCommit(fixture.analysisId());
    }

    @Test
    void repeatedReconciliationReturnsPersistedSnapshotWithoutAddingOrOverwritingRows() throws Exception {
        Fixture fixture = fixture(false);

        ReconciliationResult first = reconcileAnalysisService.reconcile(fixture.analysisId(), fixture.owner());
        List<ReconciliationLine> persistedLines = reconciliationLineRepository.findByAnalysisId(fixture.analysisId());
        Analysis analysisAfterFirst = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob jobAfterFirst = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();

        ReconciliationResult second = reconcileAnalysisService.reconcile(fixture.analysisId(), fixture.owner());
        Analysis analysisAfterSecond = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob jobAfterSecond = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();

        assertEquals(first, second);
        assertEquals(persistedLines, second.lines());
        assertEquals(1, reconciliationLineRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(0, discrepancyRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(analysisAfterFirst.version(), analysisAfterSecond.version());
        assertEquals(analysisAfterFirst.updatedAt(), analysisAfterSecond.updatedAt());
        assertEquals(jobAfterFirst.updatedAt(), jobAfterSecond.updatedAt());
        assertEquals(AnalysisStatus.GENERATING_REPORT, second.analysisStatus());
        assertEquals(AnalysisReconciliationStatus.MATCHED, second.reconciliationStatus());
        assertWakeObservedAfterCommit(fixture.analysisId());
    }

    @Test
    void rollsBackLineInsertFailure() throws Exception {
        Fixture fixture = fixture(true);
        failAfterInsert(reconciliationLineRepositorySpy);

        assertThrows(RuntimeException.class, () -> persist(fixture));

        assertRolledBack(fixture);
    }

    @Test
    void rollsBackDiscrepancyInsertFailure() throws Exception {
        Fixture fixture = fixture(true);
        failAfterInsert(discrepancyRepositorySpy);

        assertThrows(RuntimeException.class, () -> persist(fixture));

        assertRolledBack(fixture);
    }

    @Test
    void rollsBackEvidenceInsertFailure() throws Exception {
        Fixture fixture = fixture(true);
        failAfterInsert(evidenceRepositorySpy);

        assertThrows(RuntimeException.class, () -> persist(fixture));

        assertRolledBack(fixture);
    }

    @Test
    void rollsBackAnalysisUpdateFailure() throws Exception {
        Fixture fixture = fixture(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated failure after Analysis update");
        }).when(analysisRepositorySpy).update(any(Analysis.class));

        assertThrows(RuntimeException.class, () -> persist(fixture));

        assertRolledBack(fixture);
    }

    @Test
    void rollsBackAnalysisJobUpdateFailure() throws Exception {
        Fixture fixture = fixture(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated failure after AnalysisJob update");
        }).when(analysisJobRepositorySpy).update(any(AnalysisJob.class));

        assertThrows(RuntimeException.class, () -> persist(fixture));

        assertRolledBack(fixture);
    }

    @Test
    void rejectsStaleLockedMatchSnapshotWithoutWriting() throws Exception {
        Fixture fixture = fixture(false);
        UUID matchId = fixture.input().matches().getFirst().id();
        assertEquals(1, jdbcTemplate.update(
                "UPDATE invoward.line_item_matches SET version = version + 1 WHERE id = ?", matchId));

        assertThrows(ReconciliationInputChangedException.class, () -> persist(fixture));

        assertEquals(0, reconciliationLineRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(0, discrepancyRepository.countByAnalysisId(fixture.analysisId()));
        assertWorkflow(fixture.analysisId(), AnalysisStatus.RECONCILING);
    }

    @Test
    void detectsPartialExistingResultInsteadOfOverwritingIt() throws Exception {
        Fixture fixture = fixture(false);
        Instant now = clock.instant();
        discrepancyRepository.createAll(List.of(new Discrepancy(
                UUID.randomUUID(), fixture.analysisId(), null, DiscrepancyType.TOTAL_MISMATCH,
                "100.0000", "100.0000", BigDecimal.ZERO, null,
                "Existing partial result", DiscrepancyResolutionStatus.OPEN, null, null,
                0, null, now, now)));

        assertThrows(ReconciliationConflictException.class, () -> persist(fixture));

        assertEquals(0, reconciliationLineRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(1, discrepancyRepository.countByAnalysisId(fixture.analysisId()));
        assertWorkflow(fixture.analysisId(), AnalysisStatus.RECONCILING);
    }

    @Test
    void concurrentPersistenceUsesSingleWinningSnapshot() throws Exception {
        Fixture fixture = fixture(false);
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch startCallers = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ReconciliationPersistenceResult> first = executor.submit(
                    () -> persistAfterGate(fixture, callersReady, startCallers));
            Future<ReconciliationPersistenceResult> second = executor.submit(
                    () -> persistAfterGate(fixture, callersReady, startCallers));
            assertTrue(callersReady.await(5, TimeUnit.SECONDS), "Concurrent callers did not become ready");
            startCallers.countDown();

            ReconciliationPersistenceResult firstResult = first.get(15, TimeUnit.SECONDS);
            ReconciliationPersistenceResult secondResult = second.get(15, TimeUnit.SECONDS);

            assertTrue(firstResult.createdByThisCall() ^ secondResult.createdByThisCall());
            assertEquals(firstResult.lines().stream().map(ReconciliationLine::id).toList(),
                    secondResult.lines().stream().map(ReconciliationLine::id).toList());
            assertEquals(firstResult.analysis().reconciliationStatus(),
                    secondResult.analysis().reconciliationStatus());
            assertEquals(1, reconciliationLineRepository.countByAnalysisId(fixture.analysisId()));
            assertWorkflow(fixture.analysisId(), AnalysisStatus.GENERATING_REPORT);
            assertWakeObservedAfterCommit(fixture.analysisId());
        } finally {
            startCallers.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private ReconciliationPersistenceResult persist(Fixture fixture) {
        return transaction.persist(fixture.owner(), fixture.input(), fixture.plan(), fixture.resultStatus());
    }

    private ReconciliationPersistenceResult persistAfterGate(
            Fixture fixture, CountDownLatch callersReady, CountDownLatch startCallers) {
        callersReady.countDown();
        await(startCallers);
        return persist(fixture);
    }

    private Fixture fixture(boolean withDiscrepancy) throws Exception {
        Instant start = clock.instant().minusSeconds(120);
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        UUID analysisId = UUID.randomUUID();
        PriceTolerance tolerance = PriceTolerance.exactMatch();
        Analysis analysis = analysisRepository.create(Analysis.create(analysisId, owner, tolerance, start));
        AnalysisJob job = analysisJobRepository.create(AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, start));

        Instant uploadedAt = start.plusSeconds(1);
        analysis = analysisRepository.update(analysis.transitionToUploading(uploadedAt));
        job = analysisJobRepository.update(job.awaitMoreUploads(uploadedAt));
        Instant classifyingAt = start.plusSeconds(2);
        analysis = analysisRepository.update(analysis.markClassifying(classifyingAt));
        job = analysisJobRepository.update(job.waitForUserAtClassification(classifyingAt));
        Instant extractionAt = start.plusSeconds(3);
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(extractionAt));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(extractionAt));
        Instant matchingAt = start.plusSeconds(4);
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-1", "INV-1", "Test supplier", "USD",
                new BigDecimal("100.0000"), new BigDecimal(withDiscrepancy ? "110.0000" : "100.0000"),
                matchingAt));
        job = analysisJobRepository.update(job.waitForMatching(matchingAt));
        Instant reconciliationAt = start.plusSeconds(5);
        analysis = analysisRepository.update(analysis.beginReconciliation(reconciliationAt));
        job = analysisJobRepository.update(job.queueReconciliation(reconciliationAt));
        analysisJobRepository.update(job.startQueuedAttempt(reconciliationAt.plusSeconds(1), 3));

        DatabaseFixtures.LinePair pair = jdbcTemplate.execute(
                (ConnectionCallback<DatabaseFixtures.LinePair>) connection ->
                        DatabaseFixtures.insertReferenceAndInvoiceLines(connection, analysisId));
        jdbcTemplate.update(
                "UPDATE invoward.documents SET page_count = 1 WHERE analysis_id = ?", analysisId);
        jdbcTemplate.update("""
                UPDATE invoward.extracted_documents
                SET status = 'CONFIRMED', subtotal = 10, total = 10,
                    confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id IN (?, ?)
                """, pair.referenceExtractionId(), pair.invoiceExtractionId());
        jdbcTemplate.update("""
                UPDATE invoward.extracted_line_items
                SET description = 'Test item', quantity = 1, unit = 'each', unit_price = 10,
                    discount_amount = 0, tax_amount = 0, line_total = 10, updated_at = CURRENT_TIMESTAMP
                WHERE id IN (?, ?)
                """, pair.referenceLineId(), pair.invoiceLineId());
        jdbcTemplate.execute((ConnectionCallback<UUID>) connection -> DatabaseFixtures.insertMatch(
                connection,
                analysisId,
                pair.referenceLineId(),
                pair.invoiceLineId(),
                "MATCHED",
                "SKU",
                BigDecimal.ONE));
        LineItemMatch match = lineItemMatchRepository.findByAnalysisId(analysisId).getFirst();
        List<Document> documents = documentRepository.findByAnalysisId(analysisId);
        Document reference = documents.stream().filter(doc -> doc.role() == DocumentRole.REFERENCE)
                .findFirst().orElseThrow();
        Document invoice = documents.stream().filter(doc -> doc.role() == DocumentRole.INVOICE)
                .findFirst().orElseThrow();
        PersistedExtraction referenceExtraction = extractedDocumentRepository.findByDocumentId(reference.id())
                .orElseThrow();
        PersistedExtraction invoiceExtraction = extractedDocumentRepository.findByDocumentId(invoice.id())
                .orElseThrow();
        Analysis currentAnalysis = analysisRepository.findOwnedById(analysisId, owner, clock.instant())
                .orElseThrow();
        ReconciliationInput input = new ReconciliationInput(
                currentAnalysis, reference, invoice, Map.of(), Map.of(), List.of(match),
                currentAnalysis.priceTolerance(),
                ReconciliationInput.snapshot(List.of(match)),
                new ConfirmedEvidenceSource(reference, referenceExtraction.documentId(),
                        referenceExtraction.extraction(), referenceExtraction.lineItemIds()),
                new ConfirmedEvidenceSource(invoice, invoiceExtraction.documentId(),
                        invoiceExtraction.extraction(), invoiceExtraction.lineItemIds()));

        Instant createdAt = clock.instant();
        UUID lineId = UUID.randomUUID();
        BigDecimal unitDifference = withDiscrepancy ? new BigDecimal("2.0000") : BigDecimal.ZERO;
        ReconciliationLine line = new ReconciliationLine(
                lineId, analysisId, match.id(),
                withDiscrepancy ? ReconciliationLineStatus.DIFFERENT : ReconciliationLineStatus.MATCHED,
                null, null, "Reference part", "Invoice part",
                BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("10.0000"), new BigDecimal(withDiscrepancy ? "12.0000" : "10.0000"),
                new BigDecimal("10.0000"), new BigDecimal(withDiscrepancy ? "12.0000" : "10.0000"),
                BigDecimal.ZERO, unitDifference, unitDifference, createdAt);
        List<Discrepancy> discrepancies = withDiscrepancy
                ? List.of(new Discrepancy(
                        UUID.randomUUID(), analysisId, lineId, DiscrepancyType.UNIT_PRICE_MISMATCH,
                        "10.0000", "12.0000", unitDifference, new BigDecimal("20.000000"),
                        "The invoiced unit price differs from the reference price beyond the configured tolerance.",
                        DiscrepancyResolutionStatus.OPEN, null, null, 0, null, createdAt, createdAt))
                : List.of();
        List<DiscrepancyEvidence> evidence = withDiscrepancy
                ? List.of(
                        new DiscrepancyEvidence(UUID.randomUUID(), discrepancies.getFirst().id(),
                                reference.id(), EvidenceSide.REFERENCE, 1, "Reference source", null, createdAt),
                        new DiscrepancyEvidence(UUID.randomUUID(), discrepancies.getFirst().id(),
                                invoice.id(), EvidenceSide.INVOICE, 1, "Invoice source", null, createdAt))
                : List.of();
        ReconciliationPlan plan = new ReconciliationPlan(
                analysisId, List.of(line), discrepancies, evidence,
                withDiscrepancy ? new BigDecimal("10.0000") : BigDecimal.ZERO);
        return new Fixture(analysisId, owner, input, plan,
                withDiscrepancy ? AnalysisReconciliationStatus.REVIEW_REQUIRED : AnalysisReconciliationStatus.MATCHED);
    }

    private void assertRolledBack(Fixture fixture) {
        assertEquals(0, reconciliationLineRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(0, discrepancyRepository.countByAnalysisId(fixture.analysisId()));
        Long evidenceCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM invoward.discrepancy_evidence evidence
                JOIN invoward.discrepancies discrepancy ON discrepancy.id = evidence.discrepancy_id
                WHERE discrepancy.analysis_id = ?
                """, Long.class, fixture.analysisId());
        assertEquals(0L, evidenceCount);
        Analysis analysis = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        assertEquals(AnalysisStatus.RECONCILING, analysis.status());
        assertNull(analysis.reconciliationStatus());
        assertNull(analysis.difference());
        assertWorkflow(fixture.analysisId(), AnalysisStatus.RECONCILING);
    }

    private void assertWorkflow(UUID analysisId, AnalysisStatus expectedStage) {
        AnalysisOwner owner = fixtureOwner(analysisId);
        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, clock.instant()).orElseThrow();
        AnalysisJob job = analysisJobRepository.findByAnalysisId(analysisId).orElseThrow();
        assertEquals(expectedStage, analysis.status());
        AnalysisJobStatus expectedJobStatus = expectedStage == AnalysisStatus.GENERATING_REPORT
                ? AnalysisJobStatus.QUEUED : AnalysisJobStatus.RUNNING;
        assertEquals(expectedJobStatus, job.status());
        assertEquals(expectedStage, job.currentStage());
        assertFalse(job.retryable());
        assertEquals(expectedStage == AnalysisStatus.GENERATING_REPORT ? 0 : 1, job.attemptCount());
        assertEquals(expectedJobStatus == AnalysisJobStatus.RUNNING, job.startedAt() != null);
    }

    private void assertWakeObservedAfterCommit(UUID analysisId) {
        assertEquals(List.of("QUEUED/GENERATING_REPORT/0"), processingWakeProbe.statesFor(analysisId));
    }

    private AnalysisOwner fixtureOwner(UUID analysisId) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM invoward.analyses WHERE id = ?", UUID.class, analysisId);
        return new RegisteredUserOwner(userId);
    }

    private static void failAfterInsert(ReconciliationLineRepository repository) {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated failure after reconciliation line insert");
        }).when(repository).createAll(anyList());
    }

    private static void failAfterInsert(DiscrepancyRepository repository) {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated failure after discrepancy insert");
        }).when(repository).createAll(anyList());
    }

    private static void failAfterInsert(DiscrepancyEvidenceRepository repository) {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated failure after evidence insert");
        }).when(repository).createAll(anyList());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("test coordination wait timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test coordination was interrupted", exception);
        }
    }

    private record Fixture(
            UUID analysisId,
            AnalysisOwner owner,
            ReconciliationInput input,
            ReconciliationPlan plan,
            AnalysisReconciliationStatus resultStatus) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProcessingTestConfiguration {

        @Bean
        ProcessingWakeProbe processingWakeProbe(JdbcTemplate jdbcTemplate) {
            return new ProcessingWakeProbe(jdbcTemplate);
        }

        @Bean
        @Primary
        ProcessingWakePort processingWakePort(ProcessingWakeProbe probe) {
            return probe;
        }
    }

    static final class ProcessingWakeProbe implements ProcessingWakePort {

        private final JdbcTemplate jdbcTemplate;
        private final Map<UUID, List<String>> observedStates = new ConcurrentHashMap<>();

        ProcessingWakeProbe(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public void wake(UUID analysisId) {
            String state = jdbcTemplate.queryForObject(
                    "SELECT status::text || '/' || current_stage::text || '/' || attempt_count::text "
                            + "FROM invoward.analysis_jobs WHERE analysis_id = ?",
                    String.class,
                    analysisId);
            observedStates.computeIfAbsent(analysisId, ignored -> new CopyOnWriteArrayList<>()).add(state);
        }

        List<String> statesFor(UUID analysisId) {
            return List.copyOf(observedStates.getOrDefault(analysisId, List.of()));
        }
    }
}
