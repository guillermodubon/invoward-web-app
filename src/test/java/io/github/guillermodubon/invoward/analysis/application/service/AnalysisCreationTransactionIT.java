package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "invoward.email.provider=disabled")
class AnalysisCreationTransactionIT {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Autowired
    private CreateAnalysisTransaction transaction;

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
    void atomicallyPersistsAnalysisAndExactInitialJobState() throws Exception {
        RegisteredUserOwner owner = new RegisteredUserOwner(insertUser());
        PriceTolerance tolerance = new PriceTolerance(
                new BigDecimal("3"), new BigDecimal("12.3400"));
        Analysis analysis = Analysis.create(UUID.randomUUID(), owner, tolerance, NOW);
        AnalysisJob job = AnalysisJob.waitingForUser(UUID.randomUUID(), analysis.id(), NOW);

        Analysis persisted = transaction.create(analysis, job);

        assertEquals(analysis, persisted);
        Analysis loaded = analysisRepository.findOwnedById(analysis.id(), owner, NOW).orElseThrow();
        assertEquals(analysis.id(), loaded.id());
        assertEquals(analysis.owner(), loaded.owner());
        assertEquals(analysis.status(), loaded.status());
        assertEquals(analysis.reviewStatus(), loaded.reviewStatus());
        assertEquals(analysis.reconciliationStatus(), loaded.reconciliationStatus());
        assertNull(loaded.supplierName());
        assertNull(loaded.supplierKey());
        assertNull(loaded.referenceType());
        assertNull(loaded.referenceNumber());
        assertNull(loaded.invoiceNumber());
        assertNull(loaded.currency());
        assertNull(loaded.referenceTotal());
        assertNull(loaded.invoicedTotal());
        assertNull(loaded.difference());
        assertEquals(analysis.priceTolerance().priceTolerancePercent().compareTo(
                loaded.priceTolerance().priceTolerancePercent()), 0);
        assertEquals(analysis.priceTolerance().priceToleranceAbsolute().compareTo(
                loaded.priceTolerance().priceToleranceAbsolute()), 0);
        assertEquals(analysis.version(), loaded.version());
        assertFalse(loaded.retryable());
        assertNull(loaded.failureCode());
        assertNull(loaded.failureUserMessage());
        assertNull(loaded.completedAt());
        assertNull(loaded.expiresAt());
        assertEquals(analysis.createdAt(), loaded.createdAt());
        assertEquals(analysis.updatedAt(), loaded.updatedAt());
        AnalysisJob persistedJob = analysisJobRepository.findByAnalysisId(analysis.id()).orElseThrow();
        assertEquals(job, persistedJob);
        assertEquals(AnalysisStatus.CREATED, persisted.status());
        assertEquals(AnalysisReviewStatus.PENDING, persisted.reviewStatus());
        assertNull(persisted.reconciliationStatus());
        assertEquals(tolerance, persisted.priceTolerance());
        assertFalse(persisted.retryable());
        assertNull(persisted.expiresAt());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, persistedJob.status());
        assertEquals(AnalysisStatus.CREATED, persistedJob.currentStage());
        assertEquals(0, persistedJob.attemptCount());
        assertFalse(persistedJob.retryable());
        assertNull(persistedJob.lastErrorCode());
        assertNull(persistedJob.lastErrorMessage());
        assertNull(persistedJob.startedAt());
        assertNull(persistedJob.completedAt());
        assertEquals(1, count("invoward.analysis_jobs", "analysis_id", analysis.id()));
    }

    @Test
    void rollsBackAnalysisWhenItsInitialJobCannotBeInserted() throws Exception {
        RegisteredUserOwner owner = new RegisteredUserOwner(insertUser());
        Analysis existingAnalysis = Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), NOW);
        AnalysisJob existingJob = AnalysisJob.waitingForUser(
                UUID.randomUUID(), existingAnalysis.id(), NOW);
        transaction.create(existingAnalysis, existingJob);

        Analysis attemptedAnalysis = Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), NOW);
        AnalysisJob invalidJob = new AnalysisJob(
                UUID.randomUUID(), attemptedAnalysis.id(),
                AnalysisJobStatus.WAITING_FOR_USER, AnalysisStatus.CREATED,
                0, false, null, "x".repeat(501), null, null, NOW, NOW);

        assertThrows(DataIntegrityViolationException.class,
                () -> transaction.create(attemptedAnalysis, invalidJob));

        assertEquals(0, count("invoward.analyses", "id", attemptedAnalysis.id()));
        assertEquals(1, count("invoward.analysis_jobs", "id", existingJob.id()));
        assertFalse(analysisRepository.findOwnedById(
                attemptedAnalysis.id(), owner, NOW).isPresent());
    }

    @Test
    void doesNotInsertJobWhenAnalysisInsertFails() throws Exception {
        RegisteredUserOwner nonexistentOwner = new RegisteredUserOwner(UUID.randomUUID());
        Analysis analysis = Analysis.create(
                UUID.randomUUID(), nonexistentOwner, PriceTolerance.exactMatch(), NOW);
        AnalysisJob job = AnalysisJob.waitingForUser(UUID.randomUUID(), analysis.id(), NOW);

        assertThrows(DataIntegrityViolationException.class,
                () -> transaction.create(analysis, job));

        assertEquals(0, count("invoward.analyses", "id", analysis.id()));
        assertEquals(0, count("invoward.analysis_jobs", "id", job.id()));
    }

    private UUID insertUser() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
    }

    private int count(String table, String column, UUID value) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                value);
    }
}
