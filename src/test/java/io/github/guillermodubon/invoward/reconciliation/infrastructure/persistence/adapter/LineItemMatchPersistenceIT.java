package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class LineItemMatchPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Autowired
    private LineItemMatchRepository matchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void roundTripsEveryMatchShapeMethodConfidenceAndReviewTimestamp() {
        MatchFixture fixture = createFixture(5);
        Instant reviewedAt = NOW.plusSeconds(30);
        List<LineItemMatch> expected = List.of(
                match(fixture.analysisId(), fixture.referenceLineIds().get(0), fixture.invoiceLineIds().get(0),
                        LineMatchStatus.MATCHED, LineMatchMethod.SKU, new BigDecimal("1.0000"),
                        null, NOW, NOW),
                match(fixture.analysisId(), fixture.referenceLineIds().get(1), fixture.invoiceLineIds().get(1),
                        LineMatchStatus.MATCHED, LineMatchMethod.NORMALIZED_NAME, new BigDecimal("1.0000"),
                        null, NOW.plusSeconds(1), NOW.plusSeconds(1)),
                match(fixture.analysisId(), fixture.referenceLineIds().get(2), fixture.invoiceLineIds().get(2),
                        LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.FUZZY, new BigDecimal("0.8123"),
                        reviewedAt, NOW.plusSeconds(2), NOW.plusSeconds(2)),
                match(fixture.analysisId(), fixture.referenceLineIds().get(3), fixture.invoiceLineIds().get(3),
                        LineMatchStatus.MATCHED, LineMatchMethod.AI, new BigDecimal("0.7654"),
                        null, NOW.plusSeconds(3), NOW.plusSeconds(3)),
                match(fixture.analysisId(), fixture.referenceLineIds().get(4), null,
                        LineMatchStatus.UNMATCHED_REFERENCE, LineMatchMethod.MANUAL, null,
                        reviewedAt, NOW.plusSeconds(4), NOW.plusSeconds(4)),
                match(fixture.analysisId(), null, fixture.invoiceLineIds().get(4),
                        LineMatchStatus.UNMATCHED_INVOICE, LineMatchMethod.NONE, null,
                        null, NOW.plusSeconds(5), NOW.plusSeconds(5)));

        List<LineItemMatch> saved = matchRepository.createAll(expected);

        assertEquals(expected, saved);
        assertEquals(expected, matchRepository.findByAnalysisId(fixture.analysisId()));
        assertEquals(expected.size(), matchRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(expected.get(2), matchRepository.findByIdAndAnalysisIdForUpdate(
                expected.get(2).id(), fixture.analysisId()).orElseThrow());
        assertTrue(matchRepository.findByIdAndAnalysisIdForUpdate(
                expected.get(2).id(), UUID.randomUUID()).isEmpty());
        assertTrue(matchRepository.findByAnalysisIdForUpdate(fixture.analysisId()).containsAll(expected));
        assertEquals(new BigDecimal("0.8123"), saved.get(2).confidence());
        assertEquals(reviewedAt, saved.get(2).reviewedAt());
    }

    @Test
    void updateUsesJpaVersionAndRejectsStaleDomainVersion() {
        MatchFixture fixture = createFixture(1);
        LineItemMatch initial = matchRepository.create(match(fixture.analysisId(),
                fixture.referenceLineIds().getFirst(), fixture.invoiceLineIds().getFirst(),
                LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.AI, new BigDecimal("0.8123"),
                null, NOW, NOW));
        Instant reviewedAt = NOW.plusSeconds(10);
        LineItemMatch confirmed = new LineItemMatch(
                initial.id(), initial.analysisId(), initial.referenceLineItemId(), initial.invoiceLineItemId(),
                LineMatchStatus.MATCHED, initial.method(), initial.confidence(), initial.version(),
                reviewedAt, initial.createdAt(), reviewedAt);

        LineItemMatch updated = matchRepository.update(confirmed).orElseThrow();

        assertEquals(1, updated.version());
        assertEquals(LineMatchStatus.MATCHED, updated.status());
        assertEquals(reviewedAt, updated.reviewedAt());
        assertEquals(reviewedAt, updated.updatedAt());
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> matchRepository.update(confirmed));
        assertFalse(matchRepository.deleteByIdAndAnalysisId(UUID.randomUUID(), fixture.analysisId()));
    }

    @Test
    void referenceAndInvoiceLinesCannotBePersistedInMoreThanOneMatch() {
        MatchFixture fixture = createFixture(2);
        matchRepository.create(match(fixture.analysisId(), fixture.referenceLineIds().get(0),
                fixture.invoiceLineIds().get(0), LineMatchStatus.MATCHED, LineMatchMethod.MANUAL,
                null, null, NOW, NOW));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> matchRepository.create(match(fixture.analysisId(), fixture.referenceLineIds().get(0),
                        fixture.invoiceLineIds().get(1), LineMatchStatus.MATCHED, LineMatchMethod.MANUAL,
                        null, null, NOW.plusSeconds(1), NOW.plusSeconds(1))));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> matchRepository.create(match(fixture.analysisId(), fixture.referenceLineIds().get(1),
                        fixture.invoiceLineIds().get(0), LineMatchStatus.MATCHED, LineMatchMethod.MANUAL,
                        null, null, NOW.plusSeconds(2), NOW.plusSeconds(2))));

        assertEquals(1, matchRepository.countByAnalysisId(fixture.analysisId()));
    }

    @Test
    void deletingAnalysisCascadesItsPersistedMatches() {
        MatchFixture fixture = createFixture(1);
        LineItemMatch saved = matchRepository.create(match(fixture.analysisId(),
                fixture.referenceLineIds().getFirst(), fixture.invoiceLineIds().getFirst(),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, new BigDecimal("1.0000"),
                null, NOW, NOW));

        assertEquals(1, matchRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(1, jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", fixture.analysisId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.line_item_matches WHERE id = ?", Integer.class, saved.id()));
    }

    @Test
    void pessimisticAnalysisAndMatchReadsHoldDatabaseRowLocks() throws Exception {
        MatchFixture fixture = createFixture(1);
        LineItemMatch match = matchRepository.create(match(fixture.analysisId(),
                fixture.referenceLineIds().getFirst(), fixture.invoiceLineIds().getFirst(),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, new BigDecimal("1.0000"),
                null, NOW, NOW));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> lockHolder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                assertEquals(1, matchRepository.findByAnalysisIdForUpdate(fixture.analysisId()).size());
                assertTrue(matchRepository.findByIdAndAnalysisIdForUpdate(match.id(), fixture.analysisId()).isPresent());
                lockAcquired.countDown();
                await(releaseLock);
            }));
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "lock transaction did not start");

            Future<Integer> blockedUpdate = executor.submit(() -> {
                updateStarted.countDown();
                return jdbcTemplate.update(
                        "UPDATE invoward.line_item_matches SET reviewed_at = CURRENT_TIMESTAMP WHERE id = ?",
                        match.id());
            });
            assertTrue(updateStarted.await(5, TimeUnit.SECONDS), "concurrent update did not start");
            assertThrows(TimeoutException.class, () -> blockedUpdate.get(250, TimeUnit.MILLISECONDS));

            releaseLock.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
            assertEquals(1, blockedUpdate.get(5, TimeUnit.SECONDS));
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private MatchFixture createFixture(int lineCount) {
        return jdbcTemplate.execute((ConnectionCallback<MatchFixture>) connection -> {
            UUID analysisId = DatabaseFixtures.insertUserAnalysis(connection);
            DatabaseFixtures.LinePair pair = DatabaseFixtures.insertReferenceAndInvoiceLines(connection, analysisId);
            List<UUID> referenceLines = new ArrayList<>(List.of(pair.referenceLineId()));
            List<UUID> invoiceLines = new ArrayList<>(List.of(pair.invoiceLineId()));
            for (int position = 1; position < lineCount; position++) {
                referenceLines.add(DatabaseFixtures.insertLineItem(connection, pair.referenceExtractionId(), position));
                invoiceLines.add(DatabaseFixtures.insertLineItem(connection, pair.invoiceExtractionId(), position));
            }
            return new MatchFixture(analysisId, List.copyOf(referenceLines), List.copyOf(invoiceLines));
        });
    }

    private static LineItemMatch match(
            UUID analysisId,
            UUID referenceLineId,
            UUID invoiceLineId,
            LineMatchStatus status,
            LineMatchMethod method,
            BigDecimal confidence,
            Instant reviewedAt,
            Instant createdAt,
            Instant updatedAt) {
        return new LineItemMatch(UUID.randomUUID(), analysisId, referenceLineId, invoiceLineId,
                status, method, confidence, 0, reviewedAt, createdAt, updatedAt);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test lock was not released in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test lock wait was interrupted", exception);
        }
    }

    private record MatchFixture(UUID analysisId, List<UUID> referenceLineIds, List<UUID> invoiceLineIds) {
    }
}
