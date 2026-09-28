package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity;

import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "invoward", name = "discrepancies")
public class DiscrepancyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(name = "reconciliation_line_id")
    private UUID reconciliationLineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private DiscrepancyType type;

    @Column(name = "reference_value", length = 255)
    private String referenceValue;

    @Column(name = "invoice_value", length = 255)
    private String invoiceValue;

    @Column(name = "absolute_difference", precision = 19, scale = 4)
    private BigDecimal absoluteDifference;

    @Column(name = "percentage_difference", precision = 19, scale = 6)
    private BigDecimal percentageDifference;

    @Column(name = "explanation", nullable = false, columnDefinition = "text")
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 24)
    private DiscrepancyResolutionStatus resolutionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_reason", length = 48)
    private DiscrepancyResolutionReason resolutionReason;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public UUID getAnalysisId() { return analysisId; }
    public void setAnalysisId(UUID analysisId) { this.analysisId = analysisId; }
    public UUID getReconciliationLineId() { return reconciliationLineId; }
    public void setReconciliationLineId(UUID reconciliationLineId) { this.reconciliationLineId = reconciliationLineId; }
    public DiscrepancyType getType() { return type; }
    public void setType(DiscrepancyType type) { this.type = type; }
    public String getReferenceValue() { return referenceValue; }
    public void setReferenceValue(String referenceValue) { this.referenceValue = referenceValue; }
    public String getInvoiceValue() { return invoiceValue; }
    public void setInvoiceValue(String invoiceValue) { this.invoiceValue = invoiceValue; }
    public BigDecimal getAbsoluteDifference() { return absoluteDifference; }
    public void setAbsoluteDifference(BigDecimal absoluteDifference) { this.absoluteDifference = absoluteDifference; }
    public BigDecimal getPercentageDifference() { return percentageDifference; }
    public void setPercentageDifference(BigDecimal percentageDifference) { this.percentageDifference = percentageDifference; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public DiscrepancyResolutionStatus getResolutionStatus() { return resolutionStatus; }
    public void setResolutionStatus(DiscrepancyResolutionStatus resolutionStatus) { this.resolutionStatus = resolutionStatus; }
    public DiscrepancyResolutionReason getResolutionReason() { return resolutionReason; }
    public void setResolutionReason(DiscrepancyResolutionReason resolutionReason) { this.resolutionReason = resolutionReason; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
    public long getVersion() { return version; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    protected DiscrepancyJpaEntity() {
    }
}
