package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.ReconciliationLineJpaEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Maps immutable reconciliation snapshots to and from their JPA rows. */
@Component
public class ReconciliationLinePersistenceMapper {

    public ReconciliationLineJpaEntity toEntity(ReconciliationLine line) {
        Objects.requireNonNull(line, "line must not be null");
        ReconciliationLineJpaEntity entity = ReconciliationLineJpaEntity.create(line.id());
        entity.setAnalysisId(line.analysisId());
        entity.setLineMatchId(line.lineMatchId());
        entity.setStatus(line.status());
        entity.setReferenceItemCode(line.referenceItemCode());
        entity.setInvoiceItemCode(line.invoiceItemCode());
        entity.setReferenceDescription(line.referenceDescription());
        entity.setInvoiceDescription(line.invoiceDescription());
        entity.setReferenceQuantity(line.referenceQuantity());
        entity.setInvoiceQuantity(line.invoiceQuantity());
        entity.setReferenceUnitPrice(line.referenceUnitPrice());
        entity.setInvoiceUnitPrice(line.invoiceUnitPrice());
        entity.setReferenceLineTotal(line.referenceLineTotal());
        entity.setInvoiceLineTotal(line.invoiceLineTotal());
        entity.setQuantityDifference(line.quantityDifference());
        entity.setUnitPriceDifference(line.unitPriceDifference());
        entity.setLineTotalDifference(line.lineTotalDifference());
        entity.setCreatedAt(databasePrecision(line.createdAt()));
        return entity;
    }

    public ReconciliationLine toDomain(ReconciliationLineJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new ReconciliationLine(
                entity.getId(), entity.getAnalysisId(), entity.getLineMatchId(), entity.getStatus(),
                entity.getReferenceItemCode(), entity.getInvoiceItemCode(),
                entity.getReferenceDescription(), entity.getInvoiceDescription(),
                entity.getReferenceQuantity(), entity.getInvoiceQuantity(),
                entity.getReferenceUnitPrice(), entity.getInvoiceUnitPrice(),
                entity.getReferenceLineTotal(), entity.getInvoiceLineTotal(),
                entity.getQuantityDifference(), entity.getUnitPriceDifference(),
                entity.getLineTotalDifference(), entity.getCreatedAt());
    }

    private static Instant databasePrecision(Instant timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
