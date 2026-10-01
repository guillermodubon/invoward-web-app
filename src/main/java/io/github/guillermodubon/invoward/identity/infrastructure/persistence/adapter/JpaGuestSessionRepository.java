package io.github.guillermodubon.invoward.identity.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import io.github.guillermodubon.invoward.identity.application.port.GuestSessionRepository;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.GuestSessionJpaEntity;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataGuestSessionJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JpaGuestSessionRepository implements GuestSessionRepository {

    private final SpringDataGuestSessionJpaRepository repository;

    public JpaGuestSessionRepository(SpringDataGuestSessionJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public GuestSession create(GuestSession guestSession) {
        return repository.saveAndFlush(GuestSessionJpaEntity.fromModel(guestSession)).toModel();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GuestSession> findActive(UUID id, Instant now) {
        return repository.findByIdAndExpiresAtAfter(id, now).map(GuestSessionJpaEntity::toModel);
    }

    @Override
    public boolean touch(UUID id, Instant lastSeenAt) {
        return repository.touchIfActive(id, lastSeenAt) == 1;
    }
}
