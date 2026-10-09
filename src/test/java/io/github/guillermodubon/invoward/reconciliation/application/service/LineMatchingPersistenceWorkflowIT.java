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
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousLineMatchingInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualLineItemMatchUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualMatchAction;
import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.ai.FakeAmbiguousLineMatcher;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** Exercises the real matching, persistence and review workflow against PostgreSQL without provider I/O. */
@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class LineMatchingPersistenceWorkflowIT {

    private static final String AMBIGUOUS_DESCRIPTION = "blue paper archive storage box";

    @Autowired
    private RunLineItemMatchingService runMatching;

    @Autowired
    private UpdateLineItemMatchTransaction updateMatches;

    @Autowired
    private ConfirmLineItemMatchesService confirmMatches;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @MockitoBean
    private AmbiguousLineMatcher ambiguousLineMatcher;

    private final FakeAmbiguousLineMatcher fakeMatcher = new FakeAmbiguousLineMatcher();

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @BeforeEach
    void routeProviderCallsThroughDeterministicFake() {
        fakeMatcher.reset();
        when(ambiguousLineMatcher.suggest(any()))
                .thenAnswer(invocation -> fakeMatcher.suggest(invocation.getArgument(0)));
    }

    @Test
    void persistsCompleteAlgorithmCoverSupportsManualReviewAndConfirmsWithoutReconciliation() throws Exception {
        MatchingFixture fixture = createMatchingFixture();
        fakeMatcher.configureCandidate("C2");

        List<LineItemMatch> automatic = runMatching.run(fixture.analysisId(), fixture.owner());

        assertEquals(1, fakeMatcher.callCount());
        AmbiguousLineMatchingInput aiInput = fakeMatcher.lastInput().orElseThrow();
        assertEquals("REF", aiInput.reference().label());
        assertEquals(List.of("C1", "C2"), aiInput.invoiceCandidates().stream()
                .map(AmbiguousLineMatchingInput.Line::label).toList());
        assertCompleteCover(fixture, automatic);
        LineItemMatch exactSku = rowForReference(automatic, fixture.referenceLineIds().getFirst());
        assertEquals(LineMatchStatus.MATCHED, exactSku.status());
        assertEquals(LineMatchMethod.SKU, exactSku.method());
        assertEquals(new BigDecimal("1.0000"), exactSku.confidence());

        UUID reviewedReferenceId = fixture.referenceLineIds().get(1);
        LineItemMatch aiSuggestion = rowForReference(automatic, reviewedReferenceId);
        assertEquals(LineMatchStatus.NEEDS_REVIEW, aiSuggestion.status());
        assertEquals(LineMatchMethod.AI, aiSuggestion.method());
        assertEquals(fixture.invoiceLineIds().get(2), aiSuggestion.invoiceLineItemId());
        assertNull(aiSuggestion.reviewedAt());
        assertEquals(AnalysisStatus.AWAITING_MATCH_REVIEW,
                analysisRepository.findOwnedById(fixture.analysisId(), fixture.owner(), clock.instant())
                        .orElseThrow().status());
        assertWorkflow(fixture, AnalysisStatus.AWAITING_MATCH_REVIEW);

        List<LineItemMatch> manuallyReplaced = updateMatches.update(
                fixture.analysisId(), aiSuggestion.id(), fixture.owner(),
                new ManualLineItemMatchUpdate(0, ManualMatchAction.MATCH_WITH,
                        reviewedReferenceId, fixture.invoiceLineIds().get(1)));
        LineItemMatch manualPair = rowForReference(manuallyReplaced, reviewedReferenceId);
        assertEquals(LineMatchStatus.MATCHED, manualPair.status());
        assertEquals(LineMatchMethod.MANUAL, manualPair.method());
        assertNull(manualPair.confidence());
        assertEquals(1, manualPair.version());
        assertEquals(LineMatchStatus.UNMATCHED_INVOICE,
                rowForInvoice(manuallyReplaced, fixture.invoiceLineIds().get(2)).status());
        assertCompleteCover(fixture, manuallyReplaced);

        assertThrows(MatchConflictException.class, () -> updateMatches.update(
                fixture.analysisId(), aiSuggestion.id(), fixture.owner(),
                new ManualLineItemMatchUpdate(0, ManualMatchAction.CONFIRM, null, null)));
        List<LineItemMatch> afterStaleReview = lineItemMatchRepository.findByAnalysisId(fixture.analysisId());
        assertEquals(manuallyReplaced.size(), afterStaleReview.size());
        assertEquals(1, rowForReference(afterStaleReview, reviewedReferenceId).version());
        assertCompleteCover(fixture, afterStaleReview);

        List<LineItemMatch> manuallySplit = updateMatches.update(
                fixture.analysisId(), aiSuggestion.id(), fixture.owner(),
                new ManualLineItemMatchUpdate(1, ManualMatchAction.NO_MATCH, null, null));
        assertEquals(LineMatchStatus.UNMATCHED_REFERENCE,
                rowForReference(manuallySplit, reviewedReferenceId).status());
        assertEquals(LineMatchMethod.MANUAL,
                rowForReference(manuallySplit, reviewedReferenceId).method());
        assertEquals(LineMatchStatus.UNMATCHED_INVOICE,
                rowForInvoice(manuallySplit, fixture.invoiceLineIds().get(1)).status());
        assertCompleteCover(fixture, manuallySplit);

        confirmMatches.confirm(fixture.analysisId(), fixture.owner());
        List<LineItemMatch> confirmed = lineItemMatchRepository.findByAnalysisId(fixture.analysisId());
        assertCompleteCover(fixture, confirmed);
        assertTrue(confirmed.stream().allMatch(match -> match.reviewedAt() != null));
        assertWorkflow(fixture, AnalysisStatus.RECONCILING);
        assertEquals(0, countRows("invoward.reconciliation_lines", fixture.analysisId()));
        assertEquals(0, countRows("invoward.discrepancies", fixture.analysisId()));
        assertNoVersionEightMigration();
    }

    @Test
    void concurrentFullRunsReturnTheSinglePersistedWinner() throws Exception {
        MatchingFixture fixture = createMatchingFixture();
        fakeMatcher.configureCandidate("C2");
        CountDownLatch matcherCalls = new CountDownLatch(2);
        CountDownLatch releaseMatcher = new CountDownLatch(1);
        doAnswer(invocation -> {
            AmbiguousLineMatchingInput input = invocation.getArgument(0);
            matcherCalls.countDown();
            await(releaseMatcher);
            return fakeMatcher.suggest(input);
        }).when(ambiguousLineMatcher).suggest(any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<LineItemMatch>> first = executor.submit(
                    () -> runMatching.run(fixture.analysisId(), fixture.owner()));
            Future<List<LineItemMatch>> second = executor.submit(
                    () -> runMatching.run(fixture.analysisId(), fixture.owner()));
            assertTrue(matcherCalls.await(15, TimeUnit.SECONDS),
                    "both executions should finish AI assistance before persistence locking");
            releaseMatcher.countDown();

            List<LineItemMatch> firstResult = first.get(20, TimeUnit.SECONDS);
            List<LineItemMatch> secondResult = second.get(20, TimeUnit.SECONDS);
            assertEquals(Set.copyOf(firstResult), Set.copyOf(secondResult));
            assertEquals(2, fakeMatcher.callCount());
            assertEquals(3, lineItemMatchRepository.countByAnalysisId(fixture.analysisId()));
            assertCompleteCover(fixture, firstResult);
            assertWorkflow(fixture, AnalysisStatus.AWAITING_MATCH_REVIEW);
            assertNoVersionEightMigration();
        } finally {
            releaseMatcher.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private MatchingFixture createMatchingFixture() throws Exception {
        Instant startedAt = clock.instant().minusSeconds(120);
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), startedAt));
        AnalysisJob job = analysisJobRepository.create(
                AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, startedAt));

        Instant uploadedAt = startedAt.plusSeconds(1);
        analysis = analysisRepository.update(analysis.transitionToUploading(uploadedAt));
        job = analysisJobRepository.update(job.awaitMoreUploads(uploadedAt));
        Instant classifyingAt = startedAt.plusSeconds(2);
        analysis = analysisRepository.update(analysis.markClassifying(classifyingAt));
        job = analysisJobRepository.update(job.waitForUserAtClassification(classifyingAt));
        Instant extractionReviewAt = startedAt.plusSeconds(3);
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(extractionReviewAt));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(extractionReviewAt));
        Instant matchingAt = startedAt.plusSeconds(4);
        analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-1", "I-1", "Test Vendor", "USD",
                BigDecimal.TEN, BigDecimal.TEN, matchingAt));
        analysisJobRepository.update(job.waitForMatching(matchingAt));

        Instant documentsAt = startedAt.plusSeconds(5);
        Document reference = createDocument(analysisId, DocumentRole.REFERENCE, DocumentType.QUOTE, documentsAt);
        Document invoice = createDocument(analysisId, DocumentRole.INVOICE, DocumentType.INVOICE, documentsAt);
        List<UUID> referenceIds = createConfirmedExtraction(reference, List.of(
                new LineSpec("ABC-123", "accepted widget"),
                new LineSpec(null, AMBIGUOUS_DESCRIPTION)), documentsAt.plusSeconds(1));
        List<UUID> invoiceIds = createConfirmedExtraction(invoice, List.of(
                new LineSpec(" abc-123 ", "invoice widget"),
                new LineSpec(null, AMBIGUOUS_DESCRIPTION),
                new LineSpec(null, AMBIGUOUS_DESCRIPTION)), documentsAt.plusSeconds(1));
        return new MatchingFixture(analysisId, owner, referenceIds, invoiceIds);
    }

    private Document createDocument(UUID analysisId, DocumentRole role, DocumentType type, Instant createdAt) {
        return documentRepository.create(Document.createUploaded(
                        UUID.randomUUID(), analysisId, role, role + ".pdf", "application/pdf", 1024,
                        1, DatabaseFixtures.sha256(), "line-matching-it/" + UUID.randomUUID(), null, createdAt)
                .withDetectedType(type)
                .withConfirmedType(type));
    }

    private List<UUID> createConfirmedExtraction(Document document, List<LineSpec> specifications, Instant createdAt) {
        List<ExtractedLineItem> lines = new ArrayList<>();
        for (int position = 0; position < specifications.size(); position++) {
            LineSpec specification = specifications.get(position);
            lines.add(new ExtractedLineItem(
                    position, specification.itemCode(), specification.description(),
                    new BigDecimal("2.0000"), "each", new BigDecimal("37.1200"),
                    new BigDecimal("1.0000"), new BigDecimal("2.0000"), new BigDecimal("75.2400"),
                    1, "synthetic evidence", null));
        }
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Test Vendor", null, null, "USD",
                null, null, null, null, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "matching-test-v1", "test-model", 1, createdAt);
        Instant confirmedAt = createdAt.plusSeconds(1);
        PersistedExtraction confirmed = extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return confirmed.lineItemIds();
    }

    private void assertNoVersionEightMigration() {
        assertEquals("007", jdbcTemplate.queryForObject("""
                SELECT version
                FROM invoward.flyway_schema_history
                WHERE success = TRUE AND version IS NOT NULL
                ORDER BY installed_rank DESC
                LIMIT 1
                """, String.class));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM invoward.flyway_schema_history
                WHERE version = '008'
                """, Integer.class));
    }

    private long countRows(String table, UUID analysisId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE analysis_id = ?",
                Long.class, analysisId);
    }

    private void assertWorkflow(MatchingFixture fixture, AnalysisStatus expectedStatus) {
        Analysis analysis = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals(expectedStatus, analysis.status());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(expectedStatus, job.currentStage());
        assertEquals(0, job.attemptCount());
        assertFalse(job.retryable());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
    }

    private static void assertCompleteCover(MatchingFixture fixture, List<LineItemMatch> matches) {
        List<UUID> coveredReferences = matches.stream().map(LineItemMatch::referenceLineItemId)
                .filter(java.util.Objects::nonNull).toList();
        List<UUID> coveredInvoices = matches.stream().map(LineItemMatch::invoiceLineItemId)
                .filter(java.util.Objects::nonNull).toList();
        assertEquals(fixture.referenceLineIds().size(), coveredReferences.size());
        assertEquals(fixture.invoiceLineIds().size(), coveredInvoices.size());
        assertEquals(new HashSet<>(fixture.referenceLineIds()), new HashSet<>(coveredReferences));
        assertEquals(new HashSet<>(fixture.invoiceLineIds()), new HashSet<>(coveredInvoices));
        assertEquals(coveredReferences.size(), new HashSet<>(coveredReferences).size());
        assertEquals(coveredInvoices.size(), new HashSet<>(coveredInvoices).size());
    }

    private static LineItemMatch rowForReference(List<LineItemMatch> matches, UUID lineId) {
        return matches.stream().filter(match -> lineId.equals(match.referenceLineItemId()))
                .findFirst().orElseThrow();
    }

    private static LineItemMatch rowForInvoice(List<LineItemMatch> matches, UUID lineId) {
        return matches.stream().filter(match -> lineId.equals(match.invoiceLineItemId()))
                .findFirst().orElseThrow();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("test coordination wait timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test coordination was interrupted", exception);
        }
    }

    private record LineSpec(String itemCode, String description) {
    }

    private record MatchingFixture(
            UUID analysisId,
            AnalysisOwner owner,
            List<UUID> referenceLineIds,
            List<UUID> invoiceLineIds) {
    }
}
