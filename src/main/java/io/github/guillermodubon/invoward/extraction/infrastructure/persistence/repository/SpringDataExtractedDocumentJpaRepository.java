package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.entity.ExtractedDocumentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataExtractedDocumentJpaRepository
        extends JpaRepository<ExtractedDocumentJpaEntity, UUID> {

    Optional<ExtractedDocumentJpaEntity> findByDocumentId(UUID documentId);

    Optional<ExtractedDocumentJpaEntity> findByIdAndDocumentId(UUID id, UUID documentId);
}
