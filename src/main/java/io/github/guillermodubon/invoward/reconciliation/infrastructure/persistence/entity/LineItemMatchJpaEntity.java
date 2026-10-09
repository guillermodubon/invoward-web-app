package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity;

import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "invoward", name = "line_item_matches")
public class LineItemMatchJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(name = "reference_line_item_id")
    private UUID referenceLineItemId;

    @Column(name = "invoice_line_item_id")
    private UUID invoiceLineItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LineMatchStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 32)
    private LineMatchMethod method;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static LineItemMatchJpaEntity create(UUID id) {
        LineItemMatchJpaEntity entity = new LineItemMatchJpaEntity();
        entity.id = Objects.requireNonNull(id, "id must not be null");
        return entity;
    }

    public UUID getId() { return id; }
    public UUID getAnalysisId() { return analysisId; }
    public void setAnalysisId(UUID analysisId) { this.analysisId = analysisId; }
    public UUID getReferenceLineItemId() { return referenceLineItemId; }
    public void setReferenceLineItemId(UUID referenceLineItemId) { this.referenceLineItemId = referenceLineItemId; }
    public UUID getInvoiceLineItemId() { return invoiceLineItemId; }
    public void setInvoiceLineItemId(UUID invoiceLineItemId) { this.invoiceLineItemId = invoiceLineItemId; }
    public LineMatchStatus getStatus() { return status; }
    public void setStatus(LineMatchStatus status) { this.status = status; }
    public LineMatchMethod getMethod() { return method; }
    public void setMethod(LineMatchMethod method) { this.method = method; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    protected LineItemMatchJpaEntity() {
    }
}
