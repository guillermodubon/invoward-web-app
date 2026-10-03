package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Spring Data queries for Analysis, including owner-scoped reads. */
public interface SpringDataAnalysisJpaRepository extends JpaRepository<AnalysisJpaEntity, UUID> {

    Optional<AnalysisJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<AnalysisJpaEntity> findByIdAndGuestSessionIdAndExpiresAtAfter(
            UUID id, UUID guestSessionId, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select analysis from AnalysisJpaEntity analysis "
            + "where analysis.id = :analysisId and analysis.userId = :userId")
    Optional<AnalysisJpaEntity> findOwnedByIdForUpdateAndUserId(
            @Param("analysisId") UUID analysisId,
            @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select analysis from AnalysisJpaEntity analysis "
            + "where analysis.id = :analysisId "
            + "and analysis.guestSessionId = :guestSessionId "
            + "and analysis.expiresAt > :now")
    Optional<AnalysisJpaEntity> findOwnedByIdForUpdateAndGuestSessionId(
            @Param("analysisId") UUID analysisId,
            @Param("guestSessionId") UUID guestSessionId,
            @Param("now") Instant now);
}
