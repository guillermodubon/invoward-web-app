package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.entity.ExtractedLineItemJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataExtractedLineItemJpaRepository
        extends JpaRepository<ExtractedLineItemJpaEntity, UUID> {

    List<ExtractedLineItemJpaEntity> findByExtractedDocumentIdOrderByLinePosition(UUID extractedDocumentId);

    @Modifying
    @Query("delete from ExtractedLineItemJpaEntity item where item.extractedDocumentId = :extractedDocumentId")
    int deleteByExtractedDocumentId(@Param("extractedDocumentId") UUID extractedDocumentId);
}
