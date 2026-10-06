package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.entity.ExtractedLineItemJpaEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Maps an ordered line and its typed evidence to/from its JSONB-backed JPA row. */
@Component
public class ExtractedLineItemPersistenceMapper {

    public ExtractedLineItemJpaEntity toEntity(UUID extractedDocumentId, ExtractedLineItem line, Instant now) {
        Objects.requireNonNull(extractedDocumentId, "extractedDocumentId must not be null");
        Objects.requireNonNull(line, "line must not be null");
        Objects.requireNonNull(now, "now must not be null");
        ExtractedLineItemJpaEntity entity = ExtractedLineItemJpaEntity.create();
        entity.setExtractedDocumentId(extractedDocumentId);
        entity.setLinePosition(line.position());
        entity.setItemCode(line.itemCode());
        entity.setDescription(line.description());
        entity.setNormalizedDescription(line.normalizedDescription());
        entity.setQuantity(line.quantity());
        entity.setUnit(line.unit());
        entity.setUnitPrice(line.unitPrice());
        entity.setDiscountAmount(line.discountAmount());
        entity.setTaxAmount(line.taxAmount());
        entity.setLineTotal(line.lineTotal());
        entity.setPageNumber(line.pageNumber());
        entity.setSourceText(line.sourceText());
        entity.setBoundingBox(toJsonObject(line.boundingBox()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    public ExtractedLineItem toDomain(ExtractedLineItemJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new ExtractedLineItem(
                entity.getLinePosition(), entity.getItemCode(), entity.getDescription(),
                entity.getQuantity(), entity.getUnit(), entity.getUnitPrice(),
                entity.getDiscountAmount(), entity.getTaxAmount(), entity.getLineTotal(),
                entity.getPageNumber(), entity.getSourceText(), fromJsonObject(entity.getBoundingBox()));
    }

    private static Map<String, Object> toJsonObject(BoundingBox box) {
        if (box == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("xMin", box.xMin());
        values.put("yMin", box.yMin());
        values.put("xMax", box.xMax());
        values.put("yMax", box.yMax());
        return values;
    }

    private static BoundingBox fromJsonObject(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        if (values.size() != 4) {
            throw new IllegalStateException("Stored bounding box has an invalid shape");
        }
        return new BoundingBox(coordinate(values, "xMin"), coordinate(values, "yMin"),
                coordinate(values, "xMax"), coordinate(values, "yMax"));
    }

    private static double coordinate(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Stored bounding box has an invalid coordinate");
        }
        return number.doubleValue();
    }
}
