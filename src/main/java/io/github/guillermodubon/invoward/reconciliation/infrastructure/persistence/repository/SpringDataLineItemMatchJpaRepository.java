package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.entity.LineItemMatchJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data queries scoped to one Analysis for safe match-set persistence. */
public interface SpringDataLineItemMatchJpaRepository
        extends JpaRepository<LineItemMatchJpaEntity, UUID> {

    List<LineItemMatchJpaEntity> findByAnalysisIdOrderByCreatedAtAscIdAsc(UUID analysisId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select match from LineItemMatchJpaEntity match where match.analysisId = :analysisId "
            + "order by match.createdAt, match.id")
    List<LineItemMatchJpaEntity> findByAnalysisIdForUpdate(@Param("analysisId") UUID analysisId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select match from LineItemMatchJpaEntity match "
            + "where match.id = :matchId and match.analysisId = :analysisId")
    Optional<LineItemMatchJpaEntity> findByIdAndAnalysisIdForUpdate(
            @Param("matchId") UUID matchId,
            @Param("analysisId") UUID analysisId);

    Optional<LineItemMatchJpaEntity> findByIdAndAnalysisId(UUID matchId, UUID analysisId);

    @Modifying
    @Query("delete from LineItemMatchJpaEntity match "
            + "where match.id = :matchId and match.analysisId = :analysisId")
    int deleteByIdAndAnalysisId(@Param("matchId") UUID matchId, @Param("analysisId") UUID analysisId);

    long countByAnalysisId(UUID analysisId);
}
