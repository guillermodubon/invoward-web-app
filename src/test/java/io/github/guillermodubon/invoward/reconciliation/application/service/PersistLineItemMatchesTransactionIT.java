package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
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
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchingPlan;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.springframework.dao.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class PersistLineItemMatchesTransactionIT {

    @Autowired
    private PersistLineItemMatchesTransaction transaction;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ExtractedDocumentRepository extractedDocumentRepository;

    @MockitoSpyBean
    private LineItemMatchRepository lineItemMatchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsPerfectSetAndAdvancesAnalysisAndJobToReconciliation() throws Exception {
        MatchingFixture fixture = createFixture(2, 2);
        MatchingPlan plan = plan(fixture, false);

        List<LineItemMatch> saved = transaction.persist(fixture.owner(), plan);

        assertEquals(plan.matches().size(), saved.size());
        assertEquals(databasePrecision(plan.matches()), saved);
        assertEquals(plan.matches().size(), lineItemMatchRepository.countByAnalysisId(fixture.analysisId()));
        assertWorkflow(fixture, AnalysisStatus.RECONCILING);
    }

    @Test
    void persistsReviewSetAndLeavesAnalysisWaitingForMatchReview() throws Exception {
        MatchingFixture fixture = createFixture(1, 1);
        MatchingPlan plan = plan(fixture, true);

        List<LineItemMatch> saved = transaction.persist(fixture.owner(), plan);

        assertEquals(LineMatchStatus.NEEDS_REVIEW, saved.getFirst().status());
        assertEquals(AnalysisStatus.AWAITING_MATCH_REVIEW,
                analysisRepository.findOwnedById(fixture.analysisId(), fixture.owner(), clock.instant())
                        .orElseThrow().status());
        assertWorkflow(fixture, AnalysisStatus.AWAITING_MATCH_REVIEW);
    }

    @Test
    void rollsBackAllRowsAndWorkflowChangesWhenFailureFollowsInsert() throws Exception {
        MatchingFixture fixture = createFixture(2, 2);
        MatchingPlan plan = plan(fixture, false);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated failure after match insert");
        }).when(lineItemMatchRepository).createAll(anyList());

        assertThrows(DataAccessException.class, () -> transaction.persist(fixture.owner(), plan));

        assertEquals(0, lineItemMatchRepository.countByAnalysisId(fixture.analysisId()));
        assertWorkflow(fixture, AnalysisStatus.MATCHING);
    }

    @Test
    void returnsTheExistingCompleteSetWithoutRepeatingWorkflowTransitions() throws Exception {
        MatchingFixture fixture = createFixture(1, 1);
        MatchingPlan plan = plan(fixture, false);

        List<LineItemMatch> first = transaction.persist(fixture.owner(), plan);
        Analysis firstAnalysis = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob firstJob = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();

        List<LineItemMatch> second = transaction.persist(fixture.owner(), plan);

        assertEquals(first, second);
        assertEquals(1, lineItemMatchRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(firstAnalysis, analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow());
        assertEquals(firstJob, analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow());
    }

    @Test
    void concurrentRunsReturnTheSingleCompleteWinningSet() throws Exception {
        MatchingFixture fixture = createFixture(1, 1);
        MatchingPlan plan = plan(fixture, false);
        TransactionTemplate lockTransaction = new TransactionTemplate(transactionManager);
        CountDownLatch analysisLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch startCallers = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);

        try {
            Future<?> lockHolder = executor.submit(() -> lockTransaction.executeWithoutResult(status -> {
                assertTrue(analysisRepository.findOwnedByIdForUpdate(
                        fixture.analysisId(), fixture.owner(), clock.instant()).isPresent());
                analysisLocked.countDown();
                await(releaseLock);
            }));
            assertTrue(analysisLocked.await(5, TimeUnit.SECONDS), "Analysis lock was not acquired");

            Future<List<LineItemMatch>> first = executor.submit(() -> persistAfterGate(
                    fixture, plan, callersReady, startCallers));
            Future<List<LineItemMatch>> second = executor.submit(() -> persistAfterGate(
                    fixture, plan, callersReady, startCallers));
            assertTrue(callersReady.await(5, TimeUnit.SECONDS), "Concurrent callers did not become ready");
            startCallers.countDown();
            releaseLock.countDown();

            List<LineItemMatch> firstResult = first.get(10, TimeUnit.SECONDS);
            List<LineItemMatch> secondResult = second.get(10, TimeUnit.SECONDS);
            lockHolder.get(10, TimeUnit.SECONDS);

            assertEquals(firstResult, secondResult);
            assertEquals(1, lineItemMatchRepository.countByAnalysisId(fixture.analysisId()));
            assertWorkflow(fixture, AnalysisStatus.RECONCILING);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsAPartialExistingSetWithoutChangingWorkflow() throws Exception {
        MatchingFixture fixture = createFixture(2, 2);
        MatchingPlan completePlan = plan(fixture, false);
        lineItemMatchRepository.create(completePlan.matches().getFirst());

        assertThrows(MatchSetConflictException.class,
                () -> transaction.persist(fixture.owner(), completePlan));

        assertEquals(1, lineItemMatchRepository.countByAnalysisId(fixture.analysisId()));
        assertWorkflow(fixture, AnalysisStatus.MATCHING);
    }

    @Test
    void rejectsAPlanComputedFromDifferentConfirmedLineData() throws Exception {
        MatchingFixture fixture = createFixture(1, 1);
        MatchingPlan completePlan = plan(fixture, false);
        MatchableLineItem oldReference = fixture.referenceLines().getFirst();
        MatchableLineItem changedReference = new MatchableLineItem(
                oldReference.id(), oldReference.position(), oldReference.itemCode(),
                "Changed after matching was computed", oldReference.normalizedDescription(), oldReference.unit());
        MatchingPlan stalePlan = new MatchingPlan(
                fixture.analysisId(), List.of(changedReference), fixture.invoiceLines(), completePlan.matches());

        assertThrows(io.github.guillermodubon.invoward.reconciliation.application.exception
                        .MatchingInputNotReadyException.class,
                () -> transaction.persist(fixture.owner(), stalePlan));

        assertEquals(0, lineItemMatchRepository.countByAnalysisId(fixture.analysisId()));
        assertWorkflow(fixture, AnalysisStatus.MATCHING);
    }

    private List<LineItemMatch> persistAfterGate(
            MatchingFixture fixture,
            MatchingPlan plan,
            CountDownLatch callersReady,
            CountDownLatch startCallers) {
        callersReady.countDown();
        await(startCallers);
        return transaction.persist(fixture.owner(), plan);
    }

    private MatchingFixture createFixture(int referenceCount, int invoiceCount) throws Exception {
        Instant start = clock.instant().minusSeconds(120);
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), start));
        AnalysisJob job = analysisJobRepository.create(
                AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, start));

        Instant uploadedAt = start.plusSeconds(1);
        analysis = analysisRepository.update(analysis.transitionToUploading(uploadedAt));
        job = analysisJobRepository.update(job.awaitMoreUploads(uploadedAt));
        Instant classifyingAt = start.plusSeconds(2);
        analysis = analysisRepository.update(analysis.markClassifying(classifyingAt));
        job = analysisJobRepository.update(job.waitForUserAtClassification(classifyingAt));
        Instant confirmationAt = start.plusSeconds(3);
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(confirmationAt));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(confirmationAt));
        Instant matchingAt = start.plusSeconds(4);
        analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-1", "INV-1", "Test Vendor", "USD",
                BigDecimal.TEN, BigDecimal.TEN, matchingAt));
        analysisJobRepository.update(job.waitForMatching(matchingAt));

        Instant documentCreatedAt = start.plusSeconds(5);
        Document reference = createDocument(
                analysisId, DocumentRole.REFERENCE, DocumentType.QUOTE, documentCreatedAt);
        Document invoice = createDocument(
                analysisId, DocumentRole.INVOICE, DocumentType.INVOICE, documentCreatedAt);
        List<MatchableLineItem> referenceLines = createConfirmedExtraction(
                reference, "reference", referenceCount, documentCreatedAt.plusSeconds(1));
        List<MatchableLineItem> invoiceLines = createConfirmedExtraction(
                invoice, "invoice", invoiceCount, documentCreatedAt.plusSeconds(1));
        return new MatchingFixture(analysisId, owner, referenceLines, invoiceLines);
    }

    private Document createDocument(
            UUID analysisId, DocumentRole role, DocumentType type, Instant createdAt) {
        Document document = Document.createUploaded(
                UUID.randomUUID(), analysisId, role, role + ".pdf", "application/pdf", 1024,
                1, DatabaseFixtures.sha256(), "matching-test/" + UUID.randomUUID(), null, createdAt)
                .withDetectedType(type)
                .withConfirmedType(type);
        return documentRepository.create(document);
    }

    private List<MatchableLineItem> createConfirmedExtraction(
            Document document, String side, int lineCount, Instant extractedAt) {
        List<ExtractedLineItem> lines = new ArrayList<>();
        for (int position = 0; position < lineCount; position++) {
            lines.add(new ExtractedLineItem(
                    position, side + "-SKU-" + position, side + " item " + position,
                    new BigDecimal("1.0000"), "each", new BigDecimal("10.0000"),
                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.0000"),
                    1, null, null));
        }
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Test Vendor", null, null, "USD",
                null, null, null, null, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "matching-test-v1", "test-model", 1, extractedAt);
        Instant confirmedAt = extractedAt.plusSeconds(1);
        PersistedExtraction confirmed = extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return java.util.stream.IntStream.range(0, confirmed.extraction().lines().size())
                .mapToObj(index -> {
                    ExtractedLineItem line = confirmed.extraction().lines().get(index);
                    return new MatchableLineItem(
                            confirmed.lineItemIds().get(index), line.position(), line.itemCode(),
                            line.description(), line.normalizedDescription(), line.unit());
                })
                .toList();
    }

    private MatchingPlan plan(MatchingFixture fixture, boolean needsReview) {
        Instant createdAt = clock.instant();
        List<LineItemMatch> matches = new ArrayList<>();
        int pairedCount = Math.min(fixture.referenceLines().size(), fixture.invoiceLines().size());
        for (int index = 0; index < pairedCount; index++) {
            boolean review = needsReview && index == 0;
            matches.add(new LineItemMatch(
                    UUID.randomUUID(), fixture.analysisId(),
                    fixture.referenceLines().get(index).id(), fixture.invoiceLines().get(index).id(),
                    review ? LineMatchStatus.NEEDS_REVIEW : LineMatchStatus.MATCHED,
                    review ? LineMatchMethod.FUZZY : LineMatchMethod.SKU,
                    review ? new BigDecimal("0.8123") : new BigDecimal("1.0000"),
                    0, null, createdAt, createdAt));
        }
        for (int index = pairedCount; index < fixture.referenceLines().size(); index++) {
            matches.add(new LineItemMatch(
                    UUID.randomUUID(), fixture.analysisId(), fixture.referenceLines().get(index).id(), null,
                    LineMatchStatus.UNMATCHED_REFERENCE, LineMatchMethod.NONE,
                    null, 0, null, createdAt, createdAt));
        }
        for (int index = pairedCount; index < fixture.invoiceLines().size(); index++) {
            matches.add(new LineItemMatch(
                    UUID.randomUUID(), fixture.analysisId(), null, fixture.invoiceLines().get(index).id(),
                    LineMatchStatus.UNMATCHED_INVOICE, LineMatchMethod.NONE,
                    null, 0, null, createdAt, createdAt));
        }
        return new MatchingPlan(
                fixture.analysisId(), fixture.referenceLines(), fixture.invoiceLines(), matches);
    }

    private static List<LineItemMatch> databasePrecision(List<LineItemMatch> matches) {
        return matches.stream()
                .map(match -> new LineItemMatch(
                        match.id(),
                        match.analysisId(),
                        match.referenceLineItemId(),
                        match.invoiceLineItemId(),
                        match.status(),
                        match.method(),
                        match.confidence(),
                        match.version(),
                        databasePrecision(match.reviewedAt()),
                        databasePrecision(match.createdAt()),
                        databasePrecision(match.updatedAt())))
                .toList();
    }

    private static Instant databasePrecision(Instant timestamp) {
        return timestamp == null ? null : timestamp.truncatedTo(ChronoUnit.MICROS);
    }

    private void assertWorkflow(MatchingFixture fixture, AnalysisStatus expectedStage) {
        Analysis analysis = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals(expectedStage, analysis.status());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(expectedStage, job.currentStage());
        assertEquals(0, job.attemptCount());
        assertFalse(job.retryable());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
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

    private record MatchingFixture(
            UUID analysisId,
            AnalysisOwner owner,
            List<MatchableLineItem> referenceLines,
            List<MatchableLineItem> invoiceLines) {
    }

}
