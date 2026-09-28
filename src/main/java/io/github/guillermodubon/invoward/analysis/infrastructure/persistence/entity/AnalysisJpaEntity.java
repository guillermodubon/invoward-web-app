package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
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
@Table(schema = "invoward", name = "analyses")
public class AnalysisJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "guest_session_id")
    private UUID guestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private AnalysisStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private AnalysisReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", length = 24)
    private AnalysisReconciliationStatus reconciliationStatus;

    @Column(name = "supplier_name", length = 240)
    private String supplierName;

    @Column(name = "supplier_key", length = 240)
    private String supplierKey;

    @Column(name = "reference_type", length = 32)
    private String referenceType;

    @Column(name = "reference_number", length = 120)
    private String referenceNumber;

    @Column(name = "invoice_number", length = 120)
    private String invoiceNumber;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "reference_total", precision = 19, scale = 4)
    private BigDecimal referenceTotal;

    @Column(name = "invoiced_total", precision = 19, scale = 4)
    private BigDecimal invoicedTotal;

    @Column(name = "difference", precision = 19, scale = 4)
    private BigDecimal difference;

    @Column(name = "price_tolerance_percent", nullable = false, precision = 7, scale = 4)
    private BigDecimal priceTolerancePercent;

    @Column(name = "price_tolerance_absolute", precision = 19, scale = 4)
    private BigDecimal priceToleranceAbsolute;

    @Column(name = "failure_code", length = 80)
    private String failureCode;

    @Column(name = "failure_user_message", length = 500)
    private String failureUserMessage;

    @Column(name = "retryable", nullable = false)
    private boolean retryable;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getGuestSessionId() { return guestSessionId; }
    public void setGuestSessionId(UUID guestSessionId) { this.guestSessionId = guestSessionId; }
    public AnalysisStatus getStatus() { return status; }
    public void setStatus(AnalysisStatus status) { this.status = status; }
    public AnalysisReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(AnalysisReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public AnalysisReconciliationStatus getReconciliationStatus() { return reconciliationStatus; }
    public void setReconciliationStatus(AnalysisReconciliationStatus reconciliationStatus) { this.reconciliationStatus = reconciliationStatus; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getSupplierKey() { return supplierKey; }
    public void setSupplierKey(String supplierKey) { this.supplierKey = supplierKey; }
    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getReferenceTotal() { return referenceTotal; }
    public void setReferenceTotal(BigDecimal referenceTotal) { this.referenceTotal = referenceTotal; }
    public BigDecimal getInvoicedTotal() { return invoicedTotal; }
    public void setInvoicedTotal(BigDecimal invoicedTotal) { this.invoicedTotal = invoicedTotal; }
    public BigDecimal getDifference() { return difference; }
    public void setDifference(BigDecimal difference) { this.difference = difference; }
    public BigDecimal getPriceTolerancePercent() { return priceTolerancePercent; }
    public void setPriceTolerancePercent(BigDecimal priceTolerancePercent) { this.priceTolerancePercent = priceTolerancePercent; }
    public BigDecimal getPriceToleranceAbsolute() { return priceToleranceAbsolute; }
    public void setPriceToleranceAbsolute(BigDecimal priceToleranceAbsolute) { this.priceToleranceAbsolute = priceToleranceAbsolute; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureUserMessage() { return failureUserMessage; }
    public void setFailureUserMessage(String failureUserMessage) { this.failureUserMessage = failureUserMessage; }
    public boolean isRetryable() { return retryable; }
    public void setRetryable(boolean retryable) { this.retryable = retryable; }
    public long getVersion() { return version; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    protected AnalysisJpaEntity() {
    }
}
