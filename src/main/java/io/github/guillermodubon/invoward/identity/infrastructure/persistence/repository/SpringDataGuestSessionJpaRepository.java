package io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.GuestSessionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Spring Data access for Identity-owned guest sessions. */
public interface SpringDataGuestSessionJpaRepository
        extends JpaRepository<GuestSessionJpaEntity, UUID> {

    Optional<GuestSessionJpaEntity> findByIdAndExpiresAtAfter(UUID id, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update GuestSessionJpaEntity session
            set session.lastSeenAt = :lastSeenAt
            where session.id = :id and session.expiresAt > :lastSeenAt
            """)
    int touchIfActive(@Param("id") UUID id, @Param("lastSeenAt") Instant lastSeenAt);
}
