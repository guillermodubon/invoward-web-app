package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
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
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisMatchingNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class ConfirmLineItemMatchesServiceIT {

    @Autowired
    private ConfirmLineItemMatchesService service;

    @Autowired
    private AnalysisRepository analysisRepository;

    @MockitoSpyBean
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

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void confirmsCompleteCoverWithUnmatchedLinesAndPreservesExistingReviewData() throws Exception {
        Fixture fixture = createFixture(2, 1, true);
        Instant priorReview = clock.instant().minusSeconds(15);
        LineItemMatch reviewedPair = match(
                fixture, fixture.referenceLines().getFirst().id(), fixture.invoiceLines().getFirst().id(),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE, priorReview);
        LineItemMatch unmatchedReference = match(
                fixture, fixture.referenceLines().get(1).id(), null,
                LineMatchStatus.UNMATCHED_REFERENCE, LineMatchMethod.MANUAL, null, null);
        lineItemMatchRepository.createAll(List.of(reviewedPair, unmatchedReference));

        Instant before = clock.instant();
        service.confirm(fixture.analysisId(), fixture.owner());
        Instant after = clock.instant();

        List<LineItemMatch> confirmed = lineItemMatchRepository.findByAnalysisId(fixture.analysisId());
        LineItemMatch preserved = find(confirmed, reviewedPair.id());
        LineItemMatch stamped = find(confirmed, unmatchedReference.id());
        assertWorkflow(fixture, AnalysisStatus.RECONCILING);
        assertEquals(LineMatchStatus.MATCHED, preserved.status());
        assertEquals(LineMatchMethod.SKU, preserved.method());
        assertDecimalEquals(BigDecimal.ONE, preserved.confidence());
        assertEquals(databasePrecision(priorReview), preserved.reviewedAt());
        assertEquals(0, preserved.version());
        assertEquals(LineMatchStatus.UNMATCHED_REFERENCE, stamped.status());
        assertEquals(LineMatchMethod.MANUAL, stamped.method());
        assertNull(stamped.confidence());
        assertBetween(before, after, stamped.reviewedAt());
        assertEquals(1, stamped.version());
        assertEquals(0, countRows("invoward.reconciliation_lines", fixture.analysisId()));
        assertEquals(0, countRows("invoward.discrepancies", fixture.analysisId()));
    }

    @Test
    void rejectsPendingReviewWithoutChangingRowsOrWorkflow() throws Exception {
        Fixture fixture = createFixture(1, 1, true);
        LineItemMatch needsReview = match(
                fixture, fixture.referenceLines().getFirst().id(), fixture.invoiceLines().getFirst().id(),
                LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.AI, new BigDecimal("0.8123"), null);
        lineItemMatchRepository.create(needsReview);

        assertThrows(MatchSetConflictException.class, () -> service.confirm(fixture.analysisId(), fixture.owner()));

        assertMatchesEqual(List.of(needsReview), lineItemMatchRepository.findByAnalysisId(fixture.analysisId()));
        assertWorkflow(fixture, AnalysisStatus.AWAITING_MATCH_REVIEW);
    }

    @Test
    void rejectsIncompleteCoverWithoutAdvancingWorkflow() throws Exception {
        Fixture fixture = createFixture(2, 2, true);
        LineItemMatch onlyPair = match(
                fixture, fixture.referenceLines().getFirst().id(), fixture.invoiceLines().getFirst().id(),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE, null);
        lineItemMatchRepository.create(onlyPair);

        assertThrows(MatchSetConflictException.class, () -> service.confirm(fixture.analysisId(), fixture.owner()));

        assertMatchesEqual(List.of(onlyPair), lineItemMatchRepository.findByAnalysisId(fixture.analysisId()));
        assertWorkflow(fixture, AnalysisStatus.AWAITING_MATCH_REVIEW);
    }

    @Test
    void onlyAllowsConfirmationWhileWaitingForMatchReview() throws Exception {
        Fixture fixture = createFixture(1, 1, false);

        assertThrows(AnalysisMatchingNotAllowedException.class,
                () -> service.confirm(fixture.analysisId(), fixture.owner()));

        assertWorkflow(fixture, AnalysisStatus.MATCHING);
        assertEquals(0, lineItemMatchRepository.countByAnalysisId(fixture.analysisId()));
    }

    @Test
    void completeSetCanBeConfirmedIdempotentlyAfterReconciliationStarted() throws Exception {
        Fixture fixture = createFixture(1, 1, true);
        LineItemMatch completePair = match(
                fixture, fixture.referenceLines().getFirst().id(), fixture.invoiceLines().getFirst().id(),
                LineMatchStatus.MATCHED, LineMatchMethod.NORMALIZED_NAME, BigDecimal.ONE, null);
        lineItemMatchRepository.create(completePair);
        moveToReconciling(fixture);

        service.confirm(fixture.analysisId(), fixture.owner());
        service.confirm(fixture.analysisId(), fixture.owner());

        assertWorkflow(fixture, AnalysisStatus.RECONCILING);
        assertMatchesEqual(List.of(completePair), lineItemMatchRepository.findByAnalysisId(fixture.analysisId()));
    }

    @Test
    void rollsBackReviewStampsAndAnalysisTransitionWhenJobUpdateFails() throws Exception {
        Fixture fixture = createFixture(1, 1, true);
        LineItemMatch pair = match(
                fixture, fixture.referenceLines().getFirst().id(), fixture.invoiceLines().getFirst().id(),
                LineMatchStatus.MATCHED, LineMatchMethod.FUZZY, new BigDecimal("0.9000"), null);
        lineItemMatchRepository.create(pair);
        doThrow(new IllegalStateException("simulated job update failure"))
                .when(analysisJobRepository).update(any(AnalysisJob.class));

        assertThrows(DataAccessException.class, () -> service.confirm(fixture.analysisId(), fixture.owner()));

        assertMatchesEqual(List.of(pair), lineItemMatchRepository.findByAnalysisId(fixture.analysisId()));
        assertWorkflow(fixture, AnalysisStatus.AWAITING_MATCH_REVIEW);
    }

    @Test
    void wrongOwnerCannotConfirmAnalysis() throws Exception {
        Fixture fixture = createFixture(1, 1, true);
        LineItemMatch pair = match(
                fixture, fixture.referenceLines().getFirst().id(), fixture.invoiceLines().getFirst().id(),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE, null);
        lineItemMatchRepository.create(pair);
        UUID otherUserId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);

        assertThrows(AnalysisNotFoundException.class,
                () -> service.confirm(fixture.analysisId(), new RegisteredUserOwner(otherUserId)));

        assertWorkflow(fixture, AnalysisStatus.AWAITING_MATCH_REVIEW);
        assertMatchesEqual(List.of(pair), lineItemMatchRepository.findByAnalysisId(fixture.analysisId()));
    }

    private Fixture createFixture(int referenceCount, int invoiceCount, boolean awaitMatchReview) throws Exception {
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
        Instant extractionReviewAt = start.plusSeconds(3);
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(extractionReviewAt));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(extractionReviewAt));
        Instant matchingAt = start.plusSeconds(4);
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-1", "INV-1", "Test Vendor", "USD",
                BigDecimal.TEN, BigDecimal.TEN, matchingAt));
        job = analysisJobRepository.update(job.waitForMatching(matchingAt));

        if (awaitMatchReview) {
            Instant matchReviewAt = matchingAt.plusSeconds(1);
            analysis = analysisRepository.update(analysis.awaitMatchReview(matchReviewAt));
            job = analysisJobRepository.update(job.waitForMatchReview(matchReviewAt));
        }

        Instant documentCreatedAt = start.plusSeconds(6);
        Document reference = createDocument(
                analysisId, DocumentRole.REFERENCE, DocumentType.QUOTE, documentCreatedAt);
        Document invoice = createDocument(
                analysisId, DocumentRole.INVOICE, DocumentType.INVOICE, documentCreatedAt);
        List<MatchableLineItem> referenceLines = createConfirmedExtraction(
                reference, "reference", referenceCount, documentCreatedAt.plusSeconds(1));
        List<MatchableLineItem> invoiceLines = createConfirmedExtraction(
                invoice, "invoice", invoiceCount, documentCreatedAt.plusSeconds(1));
        return new Fixture(analysisId, owner, referenceLines, invoiceLines);
    }

    private Document createDocument(UUID analysisId, DocumentRole role, DocumentType type, Instant createdAt) {
        Document document = Document.createUploaded(
                UUID.randomUUID(), analysisId, role, role + ".pdf", "application/pdf", 1024,
                1, DatabaseFixtures.sha256(), "confirmation-test/" + UUID.randomUUID(), null, createdAt)
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

    private LineItemMatch match(
            Fixture fixture,
            UUID referenceId,
            UUID invoiceId,
            LineMatchStatus status,
            LineMatchMethod method,
            BigDecimal confidence,
            Instant reviewedAt) {
        Instant createdAt = clock.instant().minusSeconds(30);
        return new LineItemMatch(
                UUID.randomUUID(), fixture.analysisId(), referenceId, invoiceId,
                status, method, confidence, 0, reviewedAt, createdAt, createdAt);
    }

    private void moveToReconciling(Fixture fixture) {
        Analysis analysis = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        Instant transitionAt = clock.instant();
        if (transitionAt.isBefore(analysis.updatedAt())) {
            transitionAt = analysis.updatedAt();
        }
        if (transitionAt.isBefore(job.updatedAt())) {
            transitionAt = job.updatedAt();
        }
        analysisRepository.update(analysis.beginReconciliation(transitionAt));
        analysisJobRepository.update(job.waitForReconciliation(transitionAt));
    }

    private void assertWorkflow(Fixture fixture, AnalysisStatus expectedStage) {
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

    private long countRows(String table, UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE analysis_id = ?", Long.class, analysisId);
    }

    private static LineItemMatch find(List<LineItemMatch> matches, UUID matchId) {
        return matches.stream().filter(match -> match.id().equals(matchId)).findFirst().orElseThrow();
    }

    private static void assertBetween(Instant before, Instant after, Instant actual) {
        assertNotNull(actual);
        assertFalse(actual.isBefore(before.truncatedTo(ChronoUnit.MICROS)));
        assertFalse(actual.isAfter(after.truncatedTo(ChronoUnit.MICROS)));
    }

    private static void assertMatchesEqual(List<LineItemMatch> expected, List<LineItemMatch> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            LineItemMatch expectedMatch = expected.get(index);
            LineItemMatch actualMatch = actual.get(index);
            assertEquals(expectedMatch.id(), actualMatch.id());
            assertEquals(expectedMatch.analysisId(), actualMatch.analysisId());
            assertEquals(expectedMatch.referenceLineItemId(), actualMatch.referenceLineItemId());
            assertEquals(expectedMatch.invoiceLineItemId(), actualMatch.invoiceLineItemId());
            assertEquals(expectedMatch.status(), actualMatch.status());
            assertEquals(expectedMatch.method(), actualMatch.method());
            if (expectedMatch.confidence() == null) {
                assertNull(actualMatch.confidence());
            } else {
                assertDecimalEquals(expectedMatch.confidence(), actualMatch.confidence());
            }
            assertEquals(expectedMatch.version(), actualMatch.version());
            assertEquals(expectedMatch.reviewedAt() == null ? null : databasePrecision(expectedMatch.reviewedAt()),
                    actualMatch.reviewedAt());
            assertEquals(databasePrecision(expectedMatch.createdAt()), actualMatch.createdAt());
            assertEquals(databasePrecision(expectedMatch.updatedAt()), actualMatch.updatedAt());
        }
    }

    private static void assertDecimalEquals(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, expected.compareTo(actual));
    }

    private static Instant databasePrecision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private record Fixture(
            UUID analysisId,
            AnalysisOwner owner,
            List<MatchableLineItem> referenceLines,
            List<MatchableLineItem> invoiceLines) {
    }
}
