package io.github.guillermodubon.invoward.reporting.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
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
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@Transactional
class GeneratedReportPersistenceIT {

    private static final Instant GENERATED_AT = Instant.parse("2026-10-01T12:00:00.123456Z");

    @Autowired
    private GeneratedReportRepository generatedReportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsAndFindsRegisteredReportMetadata() {
        UUID analysisId = insertRegisteredAnalysis();
        GeneratedReport report = newReport(analysisId, null, uniqueStorageKey());

        GeneratedReport persisted = generatedReportRepository.create(report);

        assertEquals(report, persisted);
        assertEquals(report, generatedReportRepository.findByAnalysisId(analysisId).orElseThrow());
        assertTrue(generatedReportRepository.existsByAnalysisId(analysisId));
        assertFalse(generatedReportRepository.existsByAnalysisId(UUID.randomUUID()));
    }

    @Test
    void preservesGuestAnalysisExpiryOnTheReport() {
        UUID analysisId = insertGuestAnalysis();
        Instant analysisExpiry = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.analyses WHERE id = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), analysisId);
        GeneratedReport report = newReport(analysisId, analysisExpiry, uniqueStorageKey());

        GeneratedReport persisted = generatedReportRepository.create(report);

        assertEquals(analysisExpiry, persisted.expiresAt());
        assertEquals(analysisExpiry,
                generatedReportRepository.findByAnalysisId(analysisId).orElseThrow().expiresAt());
    }

    @Test
    void databaseEnforcesOneReportPerAnalysis() {
        UUID analysisId = insertRegisteredAnalysis();
        generatedReportRepository.create(newReport(analysisId, null, uniqueStorageKey()));

        assertThrows(DataIntegrityViolationException.class,
                () -> generatedReportRepository.create(
                        newReport(analysisId, null, uniqueStorageKey())));
    }

    @Test
    void databaseEnforcesUniqueStorageKeysAcrossReports() {
        UUID firstAnalysis = insertRegisteredAnalysis();
        UUID secondAnalysis = insertRegisteredAnalysis();
        String storageKey = uniqueStorageKey();
        generatedReportRepository.create(newReport(firstAnalysis, null, storageKey));

        assertThrows(DataIntegrityViolationException.class,
                () -> generatedReportRepository.create(newReport(secondAnalysis, null, storageKey)));
    }

    @Test
    void databaseRejectsInvalidSha256() {
        UUID analysisId = insertRegisteredAnalysis();

        assertThrows(DataIntegrityViolationException.class,
                () -> insertRawReport(analysisId, uniqueStorageKey(), 10L, "A".repeat(64)));
    }

    @Test
    void databaseRejectsNonPositiveSize() {
        UUID analysisId = insertRegisteredAnalysis();

        assertThrows(DataIntegrityViolationException.class,
                () -> insertRawReport(analysisId, uniqueStorageKey(), 0L, "d".repeat(64)));
    }

    @Test
    void deletingAnalysisCascadesToGeneratedReportMetadata() {
        UUID analysisId = insertRegisteredAnalysis();
        generatedReportRepository.create(newReport(analysisId, null, uniqueStorageKey()));
        assertEquals(1L, countReports(analysisId));

        assertEquals(1, jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", analysisId));

        assertEquals(0L, countReports(analysisId));
    }

    private UUID insertRegisteredAnalysis() {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUserAnalysis);
    }

    private UUID insertGuestAnalysis() {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestAnalysis);
    }

    private GeneratedReport newReport(UUID analysisId, Instant expiresAt, String storageKey) {
        return new GeneratedReport(
                UUID.randomUUID(), analysisId, storageKey, "application/pdf", 123L,
                "e".repeat(64), expiresAt, GENERATED_AT);
    }

    private void insertRawReport(UUID analysisId, String storageKey, long sizeBytes, String sha256) {
        jdbcTemplate.update("""
                INSERT INTO invoward.generated_reports
                    (id, analysis_id, storage_key, content_type, size_bytes, sha256, generated_at)
                VALUES (?, ?, ?, 'application/pdf', ?, ?, ?)
                """, UUID.randomUUID(), analysisId, storageKey, sizeBytes, sha256,
                Timestamp.from(GENERATED_AT));
    }

    private long countReports(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoward.generated_reports WHERE analysis_id = ?",
                Long.class,
                analysisId);
    }

    private static String uniqueStorageKey() {
        return "test/reports/" + UUID.randomUUID() + ".pdf";
    }
}
