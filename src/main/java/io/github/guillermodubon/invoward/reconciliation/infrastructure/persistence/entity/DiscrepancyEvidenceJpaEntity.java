package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity;

import io.github.guillermodubon.invoward.reconciliation.domain.EvidenceSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(schema = "invoward", name = "discrepancy_evidence")
public class DiscrepancyEvidenceJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "discrepancy_id", nullable = false)
    private UUID discrepancyId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 16)
    private EvidenceSide side;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(name = "source_text", columnDefinition = "text")
    private String sourceText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bounding_box", columnDefinition = "jsonb")
    private Map<String, Object> boundingBox;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public UUID getDiscrepancyId() { return discrepancyId; }
    public void setDiscrepancyId(UUID discrepancyId) { this.discrepancyId = discrepancyId; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public EvidenceSide getSide() { return side; }
    public void setSide(EvidenceSide side) { this.side = side; }
    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
    public String getSourceText() { return sourceText; }
    public void setSourceText(String sourceText) { this.sourceText = sourceText; }
    public Map<String, Object> getBoundingBox() { return boundingBox; }
    public void setBoundingBox(Map<String, Object> boundingBox) { this.boundingBox = boundingBox; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    protected DiscrepancyEvidenceJpaEntity() {
    }
}
