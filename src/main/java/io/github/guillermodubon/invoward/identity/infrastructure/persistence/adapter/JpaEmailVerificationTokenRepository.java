package io.github.guillermodubon.invoward.identity.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.EmailVerificationTokenJpaEntity;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataEmailVerificationTokenJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
@Transactional
public class JpaEmailVerificationTokenRepository implements EmailVerificationTokenRepository {

    private final SpringDataEmailVerificationTokenJpaRepository repository;

    public JpaEmailVerificationTokenRepository(
            SpringDataEmailVerificationTokenJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void saveRegistrationToken(
            UUID userId,
            String normalizedTargetEmail,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt) {
        repository.saveAndFlush(EmailVerificationTokenJpaEntity.createRegistration(
                userId, normalizedTargetEmail, tokenHash, createdAt, expiresAt));
    }
}
