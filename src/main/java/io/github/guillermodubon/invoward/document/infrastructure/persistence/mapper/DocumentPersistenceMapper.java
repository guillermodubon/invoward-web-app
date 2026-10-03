package io.github.guillermodubon.invoward.document.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.entity.DocumentJpaEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Maps the Document domain model to and from its JPA representation. */
@Component
public class DocumentPersistenceMapper {

    public DocumentJpaEntity toEntity(Document document) {
        Objects.requireNonNull(document, "document must not be null");
        DocumentJpaEntity entity = DocumentJpaEntity.create(document.id());
        entity.setAnalysisId(document.analysisId());
        entity.setRole(document.role());
        entity.setDetectedType(document.detectedType());
        entity.setConfirmedType(document.confirmedType());
        entity.setOriginalFilename(document.originalFilename());
        entity.setContentType(document.contentType());
        entity.setSizeBytes(document.sizeBytes());
        entity.setPageCount(document.pageCount());
        entity.setSha256(document.sha256());
        entity.setStorageKey(document.storageKey());
        entity.setExpiresAt(document.expiresAt());
        entity.setCreatedAt(document.createdAt());
        return entity;
    }

    public Document toDomain(DocumentJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new Document(
                entity.getId(),
                entity.getAnalysisId(),
                entity.getRole(),
                entity.getDetectedType(),
                entity.getConfirmedType(),
                entity.getOriginalFilename(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getPageCount(),
                entity.getSha256(),
                entity.getStorageKey(),
                entity.getExpiresAt(),
                entity.getCreatedAt());
    }
}
