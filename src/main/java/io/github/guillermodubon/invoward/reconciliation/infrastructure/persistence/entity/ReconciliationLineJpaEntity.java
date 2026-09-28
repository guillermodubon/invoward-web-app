package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity;

import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "invoward", name = "reconciliation_lines")
public class ReconciliationLineJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(name = "line_match_id", nullable = false)
    private UUID lineMatchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ReconciliationLineStatus status;

    @Column(name = "reference_item_code", length = 120)
    private String referenceItemCode;

    @Column(name = "invoice_item_code", length = 120)
    private String invoiceItemCode;

    @Column(name = "reference_description", columnDefinition = "text")
    private String referenceDescription;

    @Column(name = "invoice_description", columnDefinition = "text")
    private String invoiceDescription;

    @Column(name = "reference_quantity", precision = 19, scale = 4)
    private BigDecimal referenceQuantity;

    @Column(name = "invoice_quantity", precision = 19, scale = 4)
    private BigDecimal invoiceQuantity;

    @Column(name = "reference_unit_price", precision = 19, scale = 4)
    private BigDecimal referenceUnitPrice;

    @Column(name = "invoice_unit_price", precision = 19, scale = 4)
    private BigDecimal invoiceUnitPrice;

    @Column(name = "reference_line_total", precision = 19, scale = 4)
    private BigDecimal referenceLineTotal;

    @Column(name = "invoice_line_total", precision = 19, scale = 4)
    private BigDecimal invoiceLineTotal;

    @Column(name = "quantity_difference", precision = 19, scale = 4)
    private BigDecimal quantityDifference;

    @Column(name = "unit_price_difference", precision = 19, scale = 4)
    private BigDecimal unitPriceDifference;

    @Column(name = "line_total_difference", precision = 19, scale = 4)
    private BigDecimal lineTotalDifference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public UUID getAnalysisId() { return analysisId; }
    public void setAnalysisId(UUID analysisId) { this.analysisId = analysisId; }
    public UUID getLineMatchId() { return lineMatchId; }
    public void setLineMatchId(UUID lineMatchId) { this.lineMatchId = lineMatchId; }
    public ReconciliationLineStatus getStatus() { return status; }
    public void setStatus(ReconciliationLineStatus status) { this.status = status; }
    public String getReferenceItemCode() { return referenceItemCode; }
    public void setReferenceItemCode(String referenceItemCode) { this.referenceItemCode = referenceItemCode; }
    public String getInvoiceItemCode() { return invoiceItemCode; }
    public void setInvoiceItemCode(String invoiceItemCode) { this.invoiceItemCode = invoiceItemCode; }
    public String getReferenceDescription() { return referenceDescription; }
    public void setReferenceDescription(String referenceDescription) { this.referenceDescription = referenceDescription; }
    public String getInvoiceDescription() { return invoiceDescription; }
    public void setInvoiceDescription(String invoiceDescription) { this.invoiceDescription = invoiceDescription; }
    public BigDecimal getReferenceQuantity() { return referenceQuantity; }
    public void setReferenceQuantity(BigDecimal referenceQuantity) { this.referenceQuantity = referenceQuantity; }
    public BigDecimal getInvoiceQuantity() { return invoiceQuantity; }
    public void setInvoiceQuantity(BigDecimal invoiceQuantity) { this.invoiceQuantity = invoiceQuantity; }
    public BigDecimal getReferenceUnitPrice() { return referenceUnitPrice; }
    public void setReferenceUnitPrice(BigDecimal referenceUnitPrice) { this.referenceUnitPrice = referenceUnitPrice; }
    public BigDecimal getInvoiceUnitPrice() { return invoiceUnitPrice; }
    public void setInvoiceUnitPrice(BigDecimal invoiceUnitPrice) { this.invoiceUnitPrice = invoiceUnitPrice; }
    public BigDecimal getReferenceLineTotal() { return referenceLineTotal; }
    public void setReferenceLineTotal(BigDecimal referenceLineTotal) { this.referenceLineTotal = referenceLineTotal; }
    public BigDecimal getInvoiceLineTotal() { return invoiceLineTotal; }
    public void setInvoiceLineTotal(BigDecimal invoiceLineTotal) { this.invoiceLineTotal = invoiceLineTotal; }
    public BigDecimal getQuantityDifference() { return quantityDifference; }
    public void setQuantityDifference(BigDecimal quantityDifference) { this.quantityDifference = quantityDifference; }
    public BigDecimal getUnitPriceDifference() { return unitPriceDifference; }
    public void setUnitPriceDifference(BigDecimal unitPriceDifference) { this.unitPriceDifference = unitPriceDifference; }
    public BigDecimal getLineTotalDifference() { return lineTotalDifference; }
    public void setLineTotalDifference(BigDecimal lineTotalDifference) { this.lineTotalDifference = lineTotalDifference; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    protected ReconciliationLineJpaEntity() {
    }
}
