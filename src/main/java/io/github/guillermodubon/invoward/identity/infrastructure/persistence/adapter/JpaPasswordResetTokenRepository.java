package io.github.guillermodubon.invoward.identity.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.PasswordResetTokenJpaEntity;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataPasswordResetTokenJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JpaPasswordResetTokenRepository implements PasswordResetTokenRepository {

    private final SpringDataPasswordResetTokenJpaRepository repository;

    public JpaPasswordResetTokenRepository(SpringDataPasswordResetTokenJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(PasswordResetToken token) {
        repository.saveAndFlush(PasswordResetTokenJpaEntity.fromModel(token));
    }

    @Override
    public Optional<PasswordResetToken> findByTokenHashForUpdate(String tokenHash) {
        return repository.findByTokenHashForUpdate(tokenHash).map(PasswordResetTokenJpaEntity::toModel);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> findLatestCreatedAtByUser(UUID userId) {
        return repository.findLatestCreatedAtByUser(userId);
    }

    @Override
    public boolean markUsed(String tokenHash, Instant usedAt) {
        return repository.markUsed(tokenHash, usedAt) == 1;
    }

    @Override
    public int invalidateUnusedByUser(UUID userId, Instant invalidatedAt) {
        return repository.invalidateUnusedByUser(userId, invalidatedAt);
    }
}
