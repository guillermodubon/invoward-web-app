package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Spring Data queries for Analysis, including owner-scoped reads. */
public interface SpringDataAnalysisJpaRepository extends JpaRepository<AnalysisJpaEntity, UUID> {

    Optional<AnalysisJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<AnalysisJpaEntity> findByIdAndGuestSessionIdAndExpiresAtAfter(
            UUID id, UUID guestSessionId, Instant now);
}
