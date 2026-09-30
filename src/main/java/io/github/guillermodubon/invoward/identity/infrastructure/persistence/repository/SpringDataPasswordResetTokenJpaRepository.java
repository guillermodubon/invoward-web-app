package io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.PasswordResetTokenJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Spring Data access kept inside identity infrastructure. */
public interface SpringDataPasswordResetTokenJpaRepository
        extends JpaRepository<PasswordResetTokenJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from PasswordResetTokenJpaEntity token where token.tokenHash = :tokenHash")
    Optional<PasswordResetTokenJpaEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Query("select max(token.createdAt) from PasswordResetTokenJpaEntity token where token.userId = :userId")
    Optional<Instant> findLatestCreatedAtByUser(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update PasswordResetTokenJpaEntity token
            set token.usedAt = case when token.createdAt > :invalidatedAt then token.createdAt else :invalidatedAt end
            where token.userId = :userId and token.usedAt is null
            """)
    int invalidateUnusedByUser(@Param("userId") UUID userId, @Param("invalidatedAt") Instant invalidatedAt);

    @Modifying(flushAutomatically = true)
    @Query("""
            update PasswordResetTokenJpaEntity token
            set token.usedAt = :usedAt
            where token.tokenHash = :tokenHash and token.usedAt is null
            """)
    int markUsed(@Param("tokenHash") String tokenHash, @Param("usedAt") Instant usedAt);
}
