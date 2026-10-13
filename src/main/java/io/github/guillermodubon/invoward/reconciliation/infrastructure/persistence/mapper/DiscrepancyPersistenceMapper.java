package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.DiscrepancyJpaEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Maps discrepancy findings and resolution changes to their JPA representation. */
@Component
public class DiscrepancyPersistenceMapper {

    public DiscrepancyJpaEntity toEntity(Discrepancy discrepancy) {
        Objects.requireNonNull(discrepancy, "discrepancy must not be null");
        if (discrepancy.version() != 0 || discrepancy.resolutionStatus() != DiscrepancyResolutionStatus.OPEN) {
            throw new IllegalArgumentException("New discrepancies must be open at version zero");
        }
        DiscrepancyJpaEntity entity = DiscrepancyJpaEntity.create(discrepancy.id());
        entity.setAnalysisId(discrepancy.analysisId());
        entity.setReconciliationLineId(discrepancy.reconciliationLineId());
        entity.setType(discrepancy.type());
        entity.setReferenceValue(discrepancy.referenceValue());
        entity.setInvoiceValue(discrepancy.invoiceValue());
        entity.setAbsoluteDifference(discrepancy.absoluteDifference());
        entity.setPercentageDifference(discrepancy.percentageDifference());
        entity.setExplanation(discrepancy.explanation());
        entity.setResolutionStatus(discrepancy.resolutionStatus());
        entity.setResolutionReason(discrepancy.resolutionReason());
        entity.setResolutionNote(discrepancy.resolutionNote());
        entity.setResolvedAt(databasePrecision(discrepancy.resolvedAt()));
        entity.setCreatedAt(databasePrecision(discrepancy.createdAt()));
        entity.setUpdatedAt(databasePrecision(discrepancy.updatedAt()));
        return entity;
    }

    public void updateResolution(Discrepancy discrepancy, DiscrepancyJpaEntity entity) {
        Objects.requireNonNull(discrepancy, "discrepancy must not be null");
        Objects.requireNonNull(entity, "entity must not be null");
        if (!discrepancy.id().equals(entity.getId())
                || !discrepancy.analysisId().equals(entity.getAnalysisId())) {
            throw new IllegalArgumentException("Discrepancy identity does not match persistence entity identity");
        }
        entity.setResolutionStatus(discrepancy.resolutionStatus());
        entity.setResolutionReason(discrepancy.resolutionReason());
        entity.setResolutionNote(discrepancy.resolutionNote());
        entity.setResolvedAt(databasePrecision(discrepancy.resolvedAt()));
        entity.setUpdatedAt(databasePrecision(discrepancy.updatedAt()));
    }

    public Discrepancy toDomain(DiscrepancyJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new Discrepancy(
                entity.getId(), entity.getAnalysisId(), entity.getReconciliationLineId(), entity.getType(),
                entity.getReferenceValue(), entity.getInvoiceValue(), entity.getAbsoluteDifference(),
                entity.getPercentageDifference(), entity.getExplanation(), entity.getResolutionStatus(),
                entity.getResolutionReason(), entity.getResolutionNote(), entity.getVersion(),
                entity.getResolvedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static Instant databasePrecision(Instant timestamp) {
        return timestamp == null ? null : timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
