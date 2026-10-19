package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@Transactional
class AnalysisPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsRegisteredOwnerAndReturnsAnalysisOnlyForMatchingAccount() throws Exception {
        UUID userId = insertUser();
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        Analysis analysis = Analysis.create(
                UUID.randomUUID(), owner,
                new PriceTolerance(new BigDecimal("3"), new BigDecimal("12.3400")), NOW);

        Analysis persisted = analysisRepository.create(analysis);

        assertEquals(analysis, persisted);
        assertEquals(analysis, analysisRepository.findOwnedById(analysis.id(), owner, NOW).orElseThrow());
        assertEquals(analysis, analysisRepository.findOwnedByIdForUpdate(analysis.id(), owner, NOW).orElseThrow());
        assertFalse(analysisRepository.findOwnedById(
                analysis.id(), new RegisteredUserOwner(UUID.randomUUID()), NOW).isPresent());
        assertFalse(analysisRepository.findOwnedByIdForUpdate(
                analysis.id(), new RegisteredUserOwner(UUID.randomUUID()), NOW).isPresent());
        assertFalse(analysisJobRepository.findByAnalysisId(analysis.id()).isPresent());
        assertEquals(userId, jdbcTemplate.queryForObject(
                "SELECT user_id FROM invoward.analyses WHERE id = ?", UUID.class, analysis.id()));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT guest_session_id FROM invoward.analyses WHERE id = ?", UUID.class, analysis.id()));
        assertNull(persisted.expiresAt());
    }

    @Test
    void persistsGuestOwnerFixedExpiryAndUsesExpiryInOwnerScopedRead() throws Exception {
        UUID guestSessionId = insertGuestSession();
        Instant expiry = NOW.plusSeconds(86_400);
        GuestSessionOwner owner = new GuestSessionOwner(guestSessionId, expiry);
        Analysis analysis = Analysis.create(UUID.randomUUID(), owner, PriceTolerance.exactMatch(), NOW);

        Analysis persisted = analysisRepository.create(analysis);

        assertEquals(analysis, persisted);
        assertEquals(analysis, analysisRepository.findOwnedById(analysis.id(), owner, NOW).orElseThrow());
        assertEquals(analysis, analysisRepository.findOwnedByIdForUpdate(analysis.id(), owner, NOW).orElseThrow());
        assertFalse(analysisRepository.findOwnedById(
                analysis.id(), owner, expiry).isPresent());
        assertFalse(analysisRepository.findOwnedByIdForUpdate(
                analysis.id(), owner, expiry).isPresent());
        assertFalse(analysisRepository.findOwnedById(
                analysis.id(), new GuestSessionOwner(UUID.randomUUID(), expiry), NOW).isPresent());
        assertFalse(analysisRepository.findOwnedByIdForUpdate(
                analysis.id(), new GuestSessionOwner(UUID.randomUUID(), expiry), NOW).isPresent());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT user_id FROM invoward.analyses WHERE id = ?", UUID.class, analysis.id()));
        assertEquals(guestSessionId, jdbcTemplate.queryForObject(
                "SELECT guest_session_id FROM invoward.analyses WHERE id = ?", UUID.class, analysis.id()));
        assertEquals(expiry, persisted.expiresAt());
    }

    @Test
    void persistsAndLoadsInitialJobByItsUniqueAnalysisId() throws Exception {
        UUID userId = insertUser();
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(userId), PriceTolerance.exactMatch(), NOW));
        AnalysisJob job = AnalysisJob.waitingForUser(UUID.randomUUID(), analysis.id(), NOW);

        AnalysisJob persisted = analysisJobRepository.create(job);

        assertEquals(job, persisted);
        AnalysisJob loaded = analysisJobRepository.findByAnalysisId(analysis.id()).orElseThrow();
        assertEquals(job, loaded);
        assertEquals(job, analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id()).orElseThrow());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, loaded.status());
        assertEquals(AnalysisStatus.CREATED, loaded.currentStage());
        assertEquals(0, loaded.attemptCount());
        assertEquals(AnalysisReviewStatus.PENDING,
                analysisRepository.findOwnedById(analysis.id(), analysis.owner(), NOW)
                        .orElseThrow().reviewStatus());
    }

    @Test
    void ownerScopedLocksAndUpdatesAnalysisAndJobUploadState() throws Exception {
        RegisteredUserOwner owner = new RegisteredUserOwner(insertUser());
        Analysis created = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), NOW));
        AnalysisJob initialJob = analysisJobRepository.create(
                AnalysisJob.waitingForUser(UUID.randomUUID(), created.id(), NOW));
        Instant uploadedAt = NOW.plusSeconds(10);

        Analysis lockedAnalysis = analysisRepository.findOwnedByIdForUpdate(
                created.id(), owner, uploadedAt).orElseThrow();
        Analysis persistedAnalysis = analysisRepository.update(lockedAnalysis.transitionToUploading(uploadedAt));
        AnalysisJob lockedJob = analysisJobRepository.findJobByAnalysisIdForUpdate(created.id()).orElseThrow();
        AnalysisJob persistedJob = analysisJobRepository.update(lockedJob.awaitMoreUploads(uploadedAt));

        assertEquals(AnalysisStatus.UPLOADING, persistedAnalysis.status());
        assertEquals(1, persistedAnalysis.version());
        assertEquals(uploadedAt, persistedAnalysis.updatedAt());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, persistedJob.status());
        assertEquals(AnalysisStatus.UPLOADING, persistedJob.currentStage());
        assertEquals(initialJob.attemptCount(), persistedJob.attemptCount());
        assertFalse(persistedJob.retryable());
        assertNull(persistedJob.startedAt());
        assertNull(persistedJob.completedAt());
        assertEquals(uploadedAt, persistedJob.updatedAt());
        assertEquals(persistedAnalysis,
                analysisRepository.findOwnedById(created.id(), owner, uploadedAt).orElseThrow());
        assertEquals(persistedJob, analysisJobRepository.findByAnalysisId(created.id()).orElseThrow());
    }

    @Test
    void exposesInternalProcessingLookupsWithoutChangingOwnerScopedReads() throws Exception {
        RegisteredUserOwner owner = new RegisteredUserOwner(insertUser());
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), NOW));

        assertEquals(analysis, analysisRepository.findByIdForProcessing(analysis.id()).orElseThrow());
        assertEquals(analysis, analysisRepository.findByIdForProcessingForUpdate(analysis.id()).orElseThrow());
        assertTrue(analysisRepository.findOwnedById(
                analysis.id(), new RegisteredUserOwner(UUID.randomUUID()), NOW).isEmpty());

        Analysis updated = analysisRepository.update(
                analysisRepository.findByIdForProcessingForUpdate(analysis.id()).orElseThrow()
                        .transitionToUploading(NOW.plusSeconds(1)));

        assertEquals(AnalysisStatus.UPLOADING, updated.status());
        assertEquals(1, updated.version());
        assertEquals(updated, analysisRepository.findByIdForProcessing(analysis.id()).orElseThrow());
    }

    @Test
    void internalProcessingLookupCanFindExpiredGuestWhileOwnerScopedReadStillHidesIt() throws Exception {
        UUID guestSessionId = insertGuestSession();
        Instant expiresAt = NOW.plusSeconds(10);
        GuestSessionOwner owner = new GuestSessionOwner(guestSessionId, expiresAt);
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), NOW));
        Instant afterExpiry = expiresAt.plusNanos(1);

        assertTrue(analysisRepository.findOwnedById(analysis.id(), owner, afterExpiry).isEmpty());
        assertTrue(analysisRepository.findOwnedByIdForUpdate(analysis.id(), owner, afterExpiry).isEmpty());
        assertEquals(analysis, analysisRepository.findByIdForProcessing(analysis.id()).orElseThrow());
        assertEquals(analysis, analysisRepository.findByIdForProcessingForUpdate(analysis.id()).orElseThrow());
    }

    @Test
    void scansJobStatusesWithBoundedStableOldestFirstOrdering() throws Exception {
        // This global scan needs an isolated dataset; @Transactional rolls this deletion back.
        jdbcTemplate.update("DELETE FROM invoward.analysis_jobs");
        UUID userId = insertUser();
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID laterId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID runningId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        UUID waitingId = UUID.fromString("00000000-0000-0000-0000-000000000005");

        persistAnalysisWithJob(userId, firstId,
                queuedJob(firstId, NOW.plusSeconds(10)));
        persistAnalysisWithJob(userId, secondId,
                queuedJob(secondId, NOW.plusSeconds(10)));
        persistAnalysisWithJob(userId, laterId,
                queuedJob(laterId, NOW.plusSeconds(20)));
        persistAnalysisWithJob(userId, runningId,
                queuedJob(runningId, NOW.plusSeconds(10)).startQueuedAttempt(NOW.plusSeconds(11), 3));
        persistAnalysisWithJob(userId, waitingId,
                AnalysisJob.waitingForUser(UUID.randomUUID(), waitingId, NOW));

        assertEquals(List.of(firstId, secondId, laterId),
                analysisJobRepository.findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, 10));
        assertEquals(List.of(firstId, secondId),
                analysisJobRepository.findAnalysisIdsByStatus(AnalysisJobStatus.QUEUED, 2));
        assertEquals(List.of(runningId),
                analysisJobRepository.findAnalysisIdsByStatus(AnalysisJobStatus.RUNNING, 10));
        assertEquals(List.of(waitingId),
                analysisJobRepository.findAnalysisIdsByStatus(AnalysisJobStatus.WAITING_FOR_USER, 10));
    }

    @Test
    void rejectsASecondJobForTheSameAnalysis() throws Exception {
        UUID userId = insertUser();
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(userId), PriceTolerance.exactMatch(), NOW));
        analysisJobRepository.create(AnalysisJob.waitingForUser(UUID.randomUUID(), analysis.id(), NOW));

        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> analysisJobRepository.create(
                        AnalysisJob.waitingForUser(UUID.randomUUID(), analysis.id(), NOW)));

        assertTrue(failure.getMostSpecificCause().getMessage().contains("analysis_jobs_analysis_uq"));
    }

    @Test
    void mapperRoundTripsPopulatedAnalysisFieldsAndBigDecimalValues() throws Exception {
        RegisteredUserOwner owner = new RegisteredUserOwner(insertUser());
        Instant completedAt = NOW.plusSeconds(60);
        Analysis analysis = new Analysis(
                UUID.randomUUID(),
                owner,
                AnalysisStatus.FAILED,
                AnalysisReviewStatus.REVIEWED,
                AnalysisReconciliationStatus.MATCHED,
                "Northwind Supplies",
                "northwind supplies",
                "QUOTE",
                "Q-2026-041",
                "INV-2026-093",
                "USD",
                new BigDecimal("999999999999999.9999"),
                new BigDecimal("999999999999999.9998"),
                new BigDecimal("0.0001"),
                new PriceTolerance(new BigDecimal("5"), new BigDecimal("999999999999999.9999")),
                true,
                "PROCESSING_FAILURE",
                "The analysis could not be completed.",
                0,
                completedAt,
                null,
                NOW,
                completedAt);

        analysisRepository.create(analysis);

        assertEquals(analysis, analysisRepository.findOwnedById(analysis.id(), owner, NOW).orElseThrow());
    }

    private UUID insertUser() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
    }

    private void persistAnalysisWithJob(UUID userId, UUID analysisId, AnalysisJob job) {
        analysisRepository.create(Analysis.create(
                analysisId, new RegisteredUserOwner(userId), PriceTolerance.exactMatch(), NOW));
        analysisJobRepository.create(job);
    }

    private static AnalysisJob queuedJob(UUID analysisId, Instant queuedAt) {
        return AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, NOW)
                .awaitMoreUploads(NOW.plusSeconds(1))
                .queueClassification(queuedAt);
    }

    private UUID insertGuestSession() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
    }
}
