package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.LineItemMatchJpaEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Maps the matching domain model to and from its PostgreSQL-backed JPA row. */
@Component
public class LineItemMatchPersistenceMapper {

    public LineItemMatchJpaEntity toEntity(LineItemMatch match) {
        Objects.requireNonNull(match, "match must not be null");
        LineItemMatchJpaEntity entity = LineItemMatchJpaEntity.create(match.id());
        entity.setAnalysisId(match.analysisId());
        entity.setVersion(match.version());
        updateEntity(match, entity);
        return entity;
    }

    public void updateEntity(LineItemMatch match, LineItemMatchJpaEntity entity) {
        Objects.requireNonNull(match, "match must not be null");
        Objects.requireNonNull(entity, "entity must not be null");
        if (!match.id().equals(entity.getId()) || !match.analysisId().equals(entity.getAnalysisId())) {
            throw new IllegalArgumentException("Match identity must match persistence entity identity");
        }
        entity.setReferenceLineItemId(match.referenceLineItemId());
        entity.setInvoiceLineItemId(match.invoiceLineItemId());
        entity.setStatus(match.status());
        entity.setMethod(match.method());
        entity.setConfidence(match.confidence());
        entity.setReviewedAt(databasePrecision(match.reviewedAt()));
        entity.setCreatedAt(databasePrecision(match.createdAt()));
        entity.setUpdatedAt(databasePrecision(match.updatedAt()));
    }

    public LineItemMatch toDomain(LineItemMatchJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new LineItemMatch(
                entity.getId(),
                entity.getAnalysisId(),
                entity.getReferenceLineItemId(),
                entity.getInvoiceLineItemId(),
                entity.getStatus(),
                entity.getMethod(),
                entity.getConfidence(),
                entity.getVersion(),
                entity.getReviewedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static Instant databasePrecision(Instant timestamp) {
        return timestamp == null ? null : timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
