package io.github.guillermodubon.invoward.reporting.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.reporting.infrastructure.persistence.entity.GeneratedReportJpaEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeneratedReportPersistenceMapperTest {

    private final GeneratedReportPersistenceMapper mapper = new GeneratedReportPersistenceMapper();

    @Test
    void mapsAllPersistedMetadataInBothDirections() {
        GeneratedReport report = new GeneratedReport(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "users/u/analyses/a/reports/report.pdf",
                "application/pdf",
                98_765L,
                "b".repeat(64),
                Instant.parse("2026-10-02T12:00:00.123456Z"),
                Instant.parse("2026-10-01T12:00:00.654321Z"));

        GeneratedReportJpaEntity entity = mapper.toEntity(report);

        assertEquals(report.id(), entity.getId());
        assertEquals(report.analysisId(), entity.getAnalysisId());
        assertEquals(report.storageKey(), entity.getStorageKey());
        assertEquals(report.contentType(), entity.getContentType());
        assertEquals(report.sizeBytes(), entity.getSizeBytes());
        assertEquals(report.sha256(), entity.getSha256());
        assertEquals(report.expiresAt(), entity.getExpiresAt());
        assertEquals(report.generatedAt(), entity.getGeneratedAt());
        assertEquals(report, mapper.toDomain(entity));
    }

    @Test
    void preservesNullExpiryForRegisteredReport() {
        GeneratedReport report = new GeneratedReport(
                UUID.randomUUID(), UUID.randomUUID(), "users/u/analyses/a/reports/report.pdf",
                "application/pdf", 1L, "c".repeat(64), null,
                Instant.parse("2026-10-01T12:00:00Z"));

        GeneratedReportJpaEntity entity = mapper.toEntity(report);

        assertNull(entity.getExpiresAt());
        assertEquals(report, mapper.toDomain(entity));
    }
}
