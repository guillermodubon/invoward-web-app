package io.github.guillermodubon.invoward.document.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.entity.DocumentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data queries for document persistence, scoped by the parent Analysis. */
public interface SpringDataDocumentJpaRepository extends JpaRepository<DocumentJpaEntity, UUID> {

    List<DocumentJpaEntity> findByAnalysisId(UUID analysisId);

    Optional<DocumentJpaEntity> findByIdAndAnalysisId(UUID id, UUID analysisId);

    boolean existsByAnalysisIdAndRole(UUID analysisId, DocumentRole role);

    @Query("select coalesce(sum(document.sizeBytes), 0L) "
            + "from DocumentJpaEntity document where document.analysisId = :analysisId")
    long sumSizeBytesByAnalysisId(@Param("analysisId") UUID analysisId);
}
