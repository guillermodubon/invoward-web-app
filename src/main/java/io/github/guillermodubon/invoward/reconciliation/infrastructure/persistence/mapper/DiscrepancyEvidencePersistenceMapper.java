package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.DiscrepancyEvidenceJpaEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Maps confirmed evidence and normalized bounding boxes to and from JSONB-backed rows. */
@Component
public class DiscrepancyEvidencePersistenceMapper {

    public DiscrepancyEvidenceJpaEntity toEntity(DiscrepancyEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        DiscrepancyEvidenceJpaEntity entity = DiscrepancyEvidenceJpaEntity.create(evidence.id());
        entity.setDiscrepancyId(evidence.discrepancyId());
        entity.setDocumentId(evidence.documentId());
        entity.setSide(evidence.side());
        entity.setPageNumber(evidence.pageNumber());
        entity.setSourceText(evidence.sourceText());
        entity.setBoundingBox(toJsonObject(evidence.boundingBox()));
        entity.setCreatedAt(evidence.createdAt().truncatedTo(ChronoUnit.MICROS));
        return entity;
    }

    public DiscrepancyEvidence toDomain(DiscrepancyEvidenceJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new DiscrepancyEvidence(
                entity.getId(), entity.getDiscrepancyId(), entity.getDocumentId(), entity.getSide(),
                entity.getPageNumber(), entity.getSourceText(), fromJsonObject(entity.getBoundingBox()),
                entity.getCreatedAt());
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
        try {
            return new BoundingBox(coordinate(values, "xMin"), coordinate(values, "yMin"),
                    coordinate(values, "xMax"), coordinate(values, "yMax"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Stored bounding box is invalid", exception);
        }
    }

    private static double coordinate(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Stored bounding box has an invalid coordinate");
        }
        return number.doubleValue();
    }
}
