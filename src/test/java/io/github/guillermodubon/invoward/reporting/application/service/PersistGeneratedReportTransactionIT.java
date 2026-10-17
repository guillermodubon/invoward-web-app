package io.github.guillermodubon.invoward.reporting.application.service;

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
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportPersistenceResult;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class PersistGeneratedReportTransactionIT {

    @Autowired
    private PersistGeneratedReportTransaction transaction;

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

    @MockitoSpyBean
    private AnalysisRepository analysisRepositorySpy;

    @MockitoSpyBean
    private AnalysisJobRepository analysisJobRepositorySpy;

    @MockitoSpyBean
    private GeneratedReportRepository generatedReportRepositorySpy;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsReportAndCompletesAnalysisAndJobInOneTransaction() {
        Fixture fixture = reportReadyFixture();
        GeneratedReport candidate = report(fixture.analysisId(), null);

        GeneratedReportPersistenceResult result = transaction.persist(fixture.owner(), candidate);

        assertTrue(result.createdByThisCall());
        assertEquals(candidate, result.report());
        assertEquals(candidate, generatedReportRepository.findByAnalysisId(fixture.analysisId()).orElseThrow());
        assertEquals(1L, reportCount(fixture.analysisId()));
        Analysis completed = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob completedJob = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals(AnalysisStatus.COMPLETED, completed.status());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, completed.reconciliationStatus());
        assertEquals(new BigDecimal("15.2500"), completed.difference());
        assertEquals(0, completed.priceTolerance().priceTolerancePercent().compareTo(BigDecimal.ZERO));
        assertNull(completed.priceTolerance().priceToleranceAbsolute());
        assertEquals(candidate.generatedAt(), completed.completedAt());
        assertEquals(candidate.generatedAt(), completed.updatedAt());
        assertEquals(AnalysisJobStatus.COMPLETED, completedJob.status());
        assertEquals(AnalysisStatus.COMPLETED, completedJob.currentStage());
        assertEquals(candidate.generatedAt(), completedJob.completedAt());
        assertEquals(candidate.generatedAt(), completedJob.updatedAt());
        assertNull(completedJob.startedAt());
        assertEquals(fixture.job().attemptCount(), completedJob.attemptCount());
        assertFalse(completedJob.retryable());
    }

    @Test
    void returnsPersistedWinnerWithoutRewritingCompletedWorkflow() {
        Fixture fixture = reportReadyFixture();
        GeneratedReport winner = report(fixture.analysisId(), null);
        assertTrue(transaction.persist(fixture.owner(), winner).createdByThisCall());
        Analysis afterWinner = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob jobAfterWinner = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();

        GeneratedReportPersistenceResult loser = transaction.persist(
                fixture.owner(), report(fixture.analysisId(), null));

        assertFalse(loser.createdByThisCall());
        assertEquals(winner, loser.report());
        assertEquals(1L, reportCount(fixture.analysisId()));
        Analysis afterLoser = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob jobAfterLoser = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals(afterWinner.version(), afterLoser.version());
        assertEquals(afterWinner.updatedAt(), afterLoser.updatedAt());
        assertEquals(jobAfterWinner.updatedAt(), jobAfterLoser.updatedAt());
    }

    @Test
    void rollsBackReportAndAnalysisWhenJobUpdateFailsAfterItsDatabaseWrite() {
        Fixture fixture = reportReadyFixture();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated job update failure");
        }).when(analysisJobRepositorySpy).update(any(AnalysisJob.class));

        assertThrows(DataAccessException.class,
                () -> transaction.persist(fixture.owner(), report(fixture.analysisId(), null)));

        assertEquals(0L, reportCount(fixture.analysisId()));
        Analysis unchanged = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob unchangedJob = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals(AnalysisStatus.GENERATING_REPORT, unchanged.status());
        assertNull(unchanged.completedAt());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, unchangedJob.status());
        assertEquals(AnalysisStatus.GENERATING_REPORT, unchangedJob.currentStage());
        assertNull(unchangedJob.completedAt());
    }

    @Test
    void concurrentTransactionsPersistOneWinnerAndReturnItsReportToLoser() throws Exception {
        Fixture fixture = reportReadyFixture();
        CountDownLatch firstCreateReached = new CountDownLatch(1);
        CountDownLatch releaseFirstCreate = new CountDownLatch(1);
        CountDownLatch secondOwnerLockAttempted = new CountDownLatch(1);
        AtomicInteger createCalls = new AtomicInteger();
        AtomicInteger ownerLockCalls = new AtomicInteger();

        doAnswer(invocation -> {
            if (ownerLockCalls.incrementAndGet() == 2) {
                secondOwnerLockAttempted.countDown();
            }
            return invocation.callRealMethod();
        }).when(analysisRepositorySpy).findOwnedByIdForUpdate(
                any(UUID.class), any(AnalysisOwner.class), any(Instant.class));
        doAnswer(invocation -> {
            if (createCalls.incrementAndGet() == 1) {
                firstCreateReached.countDown();
                await(releaseFirstCreate);
            }
            return invocation.callRealMethod();
        }).when(generatedReportRepositorySpy).create(any(GeneratedReport.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            GeneratedReport firstCandidate = report(fixture.analysisId(), null);
            GeneratedReport secondCandidate = report(fixture.analysisId(), null);
            Future<GeneratedReportPersistenceResult> first = executor.submit(
                    () -> transaction.persist(fixture.owner(), firstCandidate));
            await(firstCreateReached);
            Future<GeneratedReportPersistenceResult> second = executor.submit(
                    () -> transaction.persist(fixture.owner(), secondCandidate));
            await(secondOwnerLockAttempted);
            releaseFirstCreate.countDown();

            GeneratedReportPersistenceResult firstResult = first.get(20, TimeUnit.SECONDS);
            GeneratedReportPersistenceResult secondResult = second.get(20, TimeUnit.SECONDS);

            assertTrue(firstResult.createdByThisCall());
            assertFalse(secondResult.createdByThisCall());
            assertEquals(firstCandidate.id(), firstResult.report().id());
            assertEquals(firstResult.report(), secondResult.report());
            assertEquals(1L, reportCount(fixture.analysisId()));
        } finally {
            releaseFirstCreate.countDown();
            executor.shutdownNow();
        }
    }

    private Fixture reportReadyFixture() {
        Instant start = clock.instant().minusSeconds(60);
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
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
        job = analysisJobRepository.update(job.awaitReportGeneration(start.plusSeconds(6)));
        return new Fixture(analysisId, owner, analysis, job);
    }

    private GeneratedReport report(UUID analysisId, Instant expiresAt) {
        Instant generatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return new GeneratedReport(UUID.randomUUID(), analysisId,
                "users/reports/" + UUID.randomUUID() + ".pdf", "application/pdf", 321L,
                "c".repeat(64), expiresAt, generatedAt);
    }

    private long reportCount(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoward.generated_reports WHERE analysis_id = ?",
                Long.class, analysisId);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "test coordination timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test coordination was interrupted", exception);
        }
    }

    private record Fixture(UUID analysisId, AnalysisOwner owner, Analysis analysis, AnalysisJob job) {
    }
}
