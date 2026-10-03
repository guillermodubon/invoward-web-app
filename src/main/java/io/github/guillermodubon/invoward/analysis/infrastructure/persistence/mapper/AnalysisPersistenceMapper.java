package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJpaEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** Maps the Analysis domain model to and from its JPA representation. */
@Component
public class AnalysisPersistenceMapper {

    public AnalysisJpaEntity toEntity(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        AnalysisJpaEntity entity = AnalysisJpaEntity.create(analysis.id());
        updateEntity(analysis, entity);
        return entity;
    }

    public void updateEntity(Analysis analysis, AnalysisJpaEntity entity) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(entity, "entity must not be null");
        if (!analysis.id().equals(entity.getId())) {
            throw new IllegalArgumentException("Analysis id must match persistence entity id");
        }
        mapOwner(analysis.owner(), entity);
        entity.setStatus(analysis.status());
        entity.setReviewStatus(analysis.reviewStatus());
        entity.setReconciliationStatus(analysis.reconciliationStatus());
        entity.setSupplierName(analysis.supplierName());
        entity.setSupplierKey(analysis.supplierKey());
        entity.setReferenceType(analysis.referenceType());
        entity.setReferenceNumber(analysis.referenceNumber());
        entity.setInvoiceNumber(analysis.invoiceNumber());
        entity.setCurrency(analysis.currency());
        entity.setReferenceTotal(analysis.referenceTotal());
        entity.setInvoicedTotal(analysis.invoicedTotal());
        entity.setDifference(analysis.difference());
        entity.setPriceTolerancePercent(analysis.priceTolerance().priceTolerancePercent());
        entity.setPriceToleranceAbsolute(analysis.priceTolerance().priceToleranceAbsolute());
        entity.setFailureCode(analysis.failureCode());
        entity.setFailureUserMessage(analysis.failureUserMessage());
        entity.setRetryable(analysis.retryable());
        entity.setCompletedAt(analysis.completedAt());
        entity.setExpiresAt(analysis.expiresAt());
        entity.setCreatedAt(analysis.createdAt());
        entity.setUpdatedAt(analysis.updatedAt());
    }

    public Analysis toDomain(AnalysisJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        AnalysisOwner owner = mapOwner(entity.getUserId(), entity.getGuestSessionId(), entity.getExpiresAt());
        return new Analysis(
                entity.getId(),
                owner,
                entity.getStatus(),
                entity.getReviewStatus(),
                entity.getReconciliationStatus(),
                entity.getSupplierName(),
                entity.getSupplierKey(),
                entity.getReferenceType(),
                entity.getReferenceNumber(),
                entity.getInvoiceNumber(),
                entity.getCurrency(),
                entity.getReferenceTotal(),
                entity.getInvoicedTotal(),
                entity.getDifference(),
                new PriceTolerance(entity.getPriceTolerancePercent(), entity.getPriceToleranceAbsolute()),
                entity.isRetryable(),
                entity.getFailureCode(),
                entity.getFailureUserMessage(),
                entity.getVersion(),
                entity.getCompletedAt(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static void mapOwner(AnalysisOwner owner, AnalysisJpaEntity entity) {
        if (owner instanceof RegisteredUserOwner registeredOwner) {
            entity.setUserId(registeredOwner.userId());
            entity.setGuestSessionId(null);
            return;
        }
        if (owner instanceof GuestSessionOwner guestOwner) {
            entity.setUserId(null);
            entity.setGuestSessionId(guestOwner.guestSessionId());
            return;
        }
        throw new IllegalArgumentException("Unsupported Analysis owner type");
    }

    private static AnalysisOwner mapOwner(UUID userId, UUID guestSessionId, java.time.Instant expiresAt) {
        if (userId != null && guestSessionId == null && expiresAt == null) {
            return new RegisteredUserOwner(userId);
        }
        if (userId == null && guestSessionId != null && expiresAt != null) {
            return new GuestSessionOwner(guestSessionId, expiresAt);
        }
        throw new IllegalStateException("Persisted Analysis ownership is inconsistent");
    }
}
