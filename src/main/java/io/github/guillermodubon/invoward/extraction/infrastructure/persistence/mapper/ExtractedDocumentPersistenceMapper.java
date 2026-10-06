package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.entity.ExtractedDocumentJpaEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Maps extraction headers between the application model and the JPA row. */
@Component
public class ExtractedDocumentPersistenceMapper {

    public ExtractedDocumentJpaEntity toNewEntity(
            UUID documentId,
            ExtractedDocument extraction,
            String extractorVersion,
            String modelId,
            int schemaVersion,
            Instant now) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(extraction, "extraction must not be null");
        Objects.requireNonNull(now, "now must not be null");
        ExtractedDocumentJpaEntity entity = ExtractedDocumentJpaEntity.create();
        copyMutableFields(extraction, entity);
        entity.setDocumentId(documentId);
        entity.setExtractorVersion(extractorVersion);
        entity.setModelId(modelId);
        entity.setSchemaVersion(schemaVersion);
        entity.setExtractedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    public void update(ExtractedDocument extraction, Instant now, ExtractedDocumentJpaEntity entity) {
        Objects.requireNonNull(extraction, "extraction must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(entity, "entity must not be null");
        copyMutableFields(extraction, entity);
        entity.setUpdatedAt(now);
    }

    public PersistedExtraction toModel(ExtractedDocumentJpaEntity entity, List<PersistedLineItem> persistedLines) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(persistedLines, "persistedLines must not be null");
        List<io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem> lines = persistedLines.stream()
                .map(PersistedLineItem::line)
                .toList();
        List<UUID> lineItemIds = persistedLines.stream()
                .map(PersistedLineItem::id)
                .toList();
        ExtractedDocument extraction = new ExtractedDocument(
                entity.getStatus(), entity.getExtractionSource(), entity.getConfirmedAt(),
                entity.getVendorName(), entity.getDocumentNumber(), entity.getDocumentDate(),
                entity.getCurrency(), entity.getSubtotal(), entity.getDiscountTotal(),
                entity.getTaxTotal(), entity.getTotal(), lines);
        return new PersistedExtraction(
                entity.getId(), entity.getDocumentId(), extraction, lineItemIds, entity.getExtractorVersion(),
                entity.getModelId(), entity.getSchemaVersion(), entity.getVersion(),
                entity.getExtractedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static void copyMutableFields(ExtractedDocument extraction, ExtractedDocumentJpaEntity entity) {
        entity.setStatus(extraction.status());
        entity.setExtractionSource(extraction.extractionSource());
        entity.setVendorName(extraction.vendorName());
        entity.setDocumentNumber(extraction.documentNumber());
        entity.setDocumentDate(extraction.documentDate());
        entity.setCurrency(extraction.currency());
        entity.setSubtotal(extraction.subtotal());
        entity.setDiscountTotal(extraction.discountTotal());
        entity.setTaxTotal(extraction.taxTotal());
        entity.setTotal(extraction.total());
        entity.setConfirmedAt(extraction.confirmedAt());
    }
}
