package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisReviewNotAllowedException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisReviewStatusResult;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
class ChangeAnalysisReviewStatusServiceIT {

    private final List<UUID> analysisIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @Autowired
    private ChangeAnalysisReviewStatusService service;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void cleanFixtures() {
        analysisIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM invoward.generated_reports WHERE analysis_id = ?", id));
        analysisIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM invoward.analysis_jobs WHERE analysis_id = ?", id));
        analysisIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM invoward.analysis_notes WHERE analysis_id = ?", id));
        analysisIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM invoward.analyses WHERE id = ?", id));
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.users WHERE id = ?", id));
        analysisIds.clear();
        userIds.clear();
    }

    @Test
    void persistsReviewDateWithoutChangingProcessingFinancialOrReportState() throws Exception {
        Fixture fixture = createCompletedFixture(AnalysisReviewStatus.PENDING, null);
        Analysis before = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob jobBefore = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        Map<String, Object> reportBefore = reportRow(fixture.analysis().id());

        AnalysisReviewStatusResult result = service.change(
                fixture.analysis().id(), fixture.owner().userId(), AnalysisReviewStatus.REVIEWED);

        Analysis after = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob jobAfter = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();

        assertEquals(AnalysisStatus.COMPLETED, after.status());
        assertEquals(AnalysisReviewStatus.REVIEWED, after.reviewStatus());
        assertEquals(result.reviewedAt().truncatedTo(ChronoUnit.MICROS), after.reviewedAt());
        assertEquals(result.reviewedAt().truncatedTo(ChronoUnit.MICROS), after.updatedAt());
        assertEquals(before.version() + 1, after.version());
        assertEquals(before.completedAt(), after.completedAt());
        assertEquals(before.reconciliationStatus(), after.reconciliationStatus());
        assertEquals(before.supplierName(), after.supplierName());
        assertEquals(before.supplierKey(), after.supplierKey());
        assertEquals(before.referenceNumber(), after.referenceNumber());
        assertEquals(before.invoiceNumber(), after.invoiceNumber());
        assertEquals(before.currency(), after.currency());
        assertEquals(before.referenceTotal(), after.referenceTotal());
        assertEquals(before.invoicedTotal(), after.invoicedTotal());
        assertEquals(before.difference(), after.difference());
        assertEquals(jobBefore, jobAfter);
        assertEquals(reportBefore, reportRow(fixture.analysis().id()));
    }

    @Test
    void revertingReviewClearsPersistedDateAndRepeatingCommandDoesNotWrite() throws Exception {
        Fixture fixture = createCompletedFixture(AnalysisReviewStatus.REVIEWED, clock.instant().minusSeconds(40));

        AnalysisReviewStatusResult pending = service.change(
                fixture.analysis().id(), fixture.owner().userId(), AnalysisReviewStatus.PENDING);
        Analysis pendingAnalysis = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();
        long pendingVersion = pendingAnalysis.version();
        Instant pendingUpdatedAt = pendingAnalysis.updatedAt();
        AnalysisReviewStatusResult repeated = service.change(
                fixture.analysis().id(), fixture.owner().userId(), AnalysisReviewStatus.PENDING);
        Analysis afterRepeat = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();

        assertEquals(AnalysisReviewStatus.PENDING, pending.reviewStatus());
        assertEquals(null, pending.reviewedAt());
        assertEquals(pendingVersion, afterRepeat.version());
        assertEquals(pendingUpdatedAt, afterRepeat.updatedAt());
        assertEquals(AnalysisReviewStatusResult.from(afterRepeat), repeated);
    }

    @Test
    void incompleteAnalysisCannotBeReviewedAndDoesNotChangeAnyPersistedState() throws Exception {
        UUID userId = insertUser();
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        Instant now = clock.instant().minusSeconds(30);
        Analysis created = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), now));
        analysisIds.add(created.id());
        analysisJobRepository.create(AnalysisJob.waitingForUser(
                UUID.randomUUID(), created.id(), now));
        AnalysisJob job = analysisJobRepository.findByAnalysisId(created.id()).orElseThrow();

        assertThrows(AnalysisReviewNotAllowedException.class,
                () -> service.change(created.id(), userId, AnalysisReviewStatus.REVIEWED));

        Analysis persistedBefore = analysisRepository.findOwnedById(
                created.id(), owner, clock.instant()).orElseThrow();
        assertEquals(persistedBefore, analysisRepository.findOwnedById(
                created.id(), owner, clock.instant()).orElseThrow());
        assertEquals(job, analysisJobRepository.findByAnalysisId(created.id()).orElseThrow());
    }

    @Test
    void simultaneousSameReviewCommandsSerializeAndPersistOneTransition() throws Exception {
        Fixture fixture = createCompletedFixture(AnalysisReviewStatus.PENDING, null);
        Analysis before = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob jobBefore = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AnalysisReviewStatusResult> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("review test start signal timed out");
                }
                return service.change(fixture.analysis().id(), fixture.owner().userId(), AnalysisReviewStatus.REVIEWED);
            });
            Future<AnalysisReviewStatusResult> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("review test start signal timed out");
                }
                return service.change(fixture.analysis().id(), fixture.owner().userId(), AnalysisReviewStatus.REVIEWED);
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS), "both review commands should be ready");
            start.countDown();
            AnalysisReviewStatusResult firstResult = first.get(20, TimeUnit.SECONDS);
            AnalysisReviewStatusResult secondResult = second.get(20, TimeUnit.SECONDS);
            Analysis after = analysisRepository.findOwnedById(
                    fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();

            assertEquals(AnalysisReviewStatus.REVIEWED, after.reviewStatus());
            assertNotNull(after.reviewedAt());
            assertEquals(before.version() + 1, after.version());
            assertEquals(after.reviewedAt(), firstResult.reviewedAt().truncatedTo(ChronoUnit.MICROS));
            assertEquals(after.reviewedAt(), secondResult.reviewedAt().truncatedTo(ChronoUnit.MICROS));
            assertEquals(jobBefore, analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Fixture createCompletedFixture(AnalysisReviewStatus status, Instant reviewedAt) throws Exception {
        UUID userId = insertUser();
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        Instant createdAt = clock.instant().minusSeconds(60);
        Instant completedAt = clock.instant().minusSeconds(30);
        Analysis analysis = new Analysis(
                UUID.randomUUID(), owner, AnalysisStatus.COMPLETED, status, reviewedAt,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Review Supplier", "review supplier", "QUOTE", "REF-REVIEW", "INV-REVIEW", "USD",
                new BigDecimal("75.0000"), new BigDecimal("80.0000"), new BigDecimal("5.0000"),
                PriceTolerance.exactMatch(), false, null, null, 0,
                completedAt, null, createdAt, completedAt);
        Analysis persisted = analysisRepository.create(analysis);
        analysisIds.add(persisted.id());
        Instant startedAt = completedAt.minusSeconds(10);
        AnalysisJob job = new AnalysisJob(
                UUID.randomUUID(), persisted.id(), AnalysisJobStatus.COMPLETED, AnalysisStatus.COMPLETED,
                2, false, null, null, startedAt, completedAt, createdAt, completedAt);
        AnalysisJob persistedJob = analysisJobRepository.create(job);
        jdbcTemplate.execute((ConnectionCallback<UUID>) connection -> DatabaseFixtures.insertGeneratedReport(
                connection, persisted.id(), "users/" + userId + "/analyses/" + persisted.id() + "/reports/fixture.pdf"));
        return new Fixture(persisted, owner, persistedJob);
    }

    private UUID insertUser() throws Exception {
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        userIds.add(userId);
        return userId;
    }

    private Map<String, Object> reportRow(UUID analysisId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, storage_key, content_type, size_bytes, sha256, expires_at, generated_at
                FROM invoward.generated_reports WHERE analysis_id = ?
                """, analysisId);
    }

    private record Fixture(Analysis analysis, RegisteredUserOwner owner, AnalysisJob job) {
    }
}
