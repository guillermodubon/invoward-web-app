package io.github.guillermodubon.invoward.reporting.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.reporting.infrastructure.persistence.entity.GeneratedReportJpaEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Maps generated-report metadata to and from its JPA representation. */
@Component
public class GeneratedReportPersistenceMapper {

    public GeneratedReportJpaEntity toEntity(GeneratedReport report) {
        Objects.requireNonNull(report, "report must not be null");
        GeneratedReportJpaEntity entity = GeneratedReportJpaEntity.create(report.id());
        entity.setAnalysisId(report.analysisId());
        entity.setStorageKey(report.storageKey());
        entity.setContentType(report.contentType());
        entity.setSizeBytes(report.sizeBytes());
        entity.setSha256(report.sha256());
        entity.setExpiresAt(databasePrecision(report.expiresAt()));
        entity.setGeneratedAt(databasePrecision(report.generatedAt()));
        return entity;
    }

    public GeneratedReport toDomain(GeneratedReportJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new GeneratedReport(
                entity.getId(),
                entity.getAnalysisId(),
                entity.getStorageKey(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getExpiresAt(),
                entity.getGeneratedAt());
    }

    private static Instant databasePrecision(Instant timestamp) {
        return timestamp == null ? null : timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
