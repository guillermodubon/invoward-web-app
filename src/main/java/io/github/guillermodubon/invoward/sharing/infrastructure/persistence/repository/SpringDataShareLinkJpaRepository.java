package io.github.guillermodubon.invoward.sharing.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.sharing.infrastructure.persistence.entity.ShareLinkJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Indexed and Analysis-scoped Spring Data queries for share-link persistence. */
public interface SpringDataShareLinkJpaRepository extends JpaRepository<ShareLinkJpaEntity, UUID> {

    Optional<ShareLinkJpaEntity> findByTokenHash(String tokenHash);

    Optional<ShareLinkJpaEntity> findByAnalysisIdAndId(UUID analysisId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select shareLink from ShareLinkJpaEntity shareLink "
            + "where shareLink.analysisId = :analysisId and shareLink.id = :shareLinkId")
    Optional<ShareLinkJpaEntity> findByAnalysisIdAndIdForUpdate(
            @Param("analysisId") UUID analysisId,
            @Param("shareLinkId") UUID shareLinkId);

    long countByAnalysisIdAndRevokedAtIsNullAndExpiresAtAfter(UUID analysisId, Instant now);

    Page<ShareLinkJpaEntity> findByAnalysisId(UUID analysisId, Pageable pageable);
}
