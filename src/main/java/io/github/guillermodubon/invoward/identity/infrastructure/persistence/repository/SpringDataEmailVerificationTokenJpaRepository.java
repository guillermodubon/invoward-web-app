package io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.EmailVerificationTokenJpaEntity;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
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
public interface SpringDataEmailVerificationTokenJpaRepository
        extends JpaRepository<EmailVerificationTokenJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from EmailVerificationTokenJpaEntity token where token.tokenHash = :tokenHash")
    Optional<EmailVerificationTokenJpaEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Query("""
            select max(token.createdAt)
            from EmailVerificationTokenJpaEntity token
            where token.userId = :userId and token.purpose = :purpose
            """)
    Optional<Instant> findLatestCreatedAtByUserAndPurpose(
            @Param("userId") UUID userId,
            @Param("purpose") EmailVerificationPurpose purpose);

    @Modifying(flushAutomatically = true)
    @Query("""
            update EmailVerificationTokenJpaEntity token
            set token.usedAt = case when token.createdAt > :invalidatedAt then token.createdAt else :invalidatedAt end
            where token.userId = :userId and token.purpose = :purpose and token.usedAt is null
            """)
    int invalidateUnusedByUserAndPurpose(
            @Param("userId") UUID userId,
            @Param("purpose") EmailVerificationPurpose purpose,
            @Param("invalidatedAt") Instant invalidatedAt);

    @Modifying(flushAutomatically = true)
    @Query("""
            update EmailVerificationTokenJpaEntity token
            set token.usedAt = :usedAt
            where token.tokenHash = :tokenHash and token.usedAt is null
            """)
    int markUsed(@Param("tokenHash") String tokenHash, @Param("usedAt") Instant usedAt);
}
