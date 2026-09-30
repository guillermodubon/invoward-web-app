package io.github.guillermodubon.invoward.identity.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.EmailVerificationTokenJpaEntity;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataEmailVerificationTokenJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
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
    public void save(EmailVerificationToken token) {
        repository.saveAndFlush(EmailVerificationTokenJpaEntity.fromModel(token));
    }

    @Override
    public Optional<EmailVerificationToken> findByTokenHashForUpdate(String tokenHash) {
        return repository.findByTokenHashForUpdate(tokenHash).map(EmailVerificationTokenJpaEntity::toModel);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> findLatestCreatedAtByUserAndPurpose(
            UUID userId,
            EmailVerificationPurpose purpose) {
        return repository.findLatestCreatedAtByUserAndPurpose(userId, purpose);
    }

    @Override
    public boolean markUsed(String tokenHash, Instant usedAt) {
        return repository.markUsed(tokenHash, usedAt) == 1;
    }

    @Override
    public int invalidateUnusedByUserAndPurpose(
            UUID userId,
            EmailVerificationPurpose purpose,
            Instant invalidatedAt) {
        return repository.invalidateUnusedByUserAndPurpose(userId, purpose, invalidatedAt);
    }
}
