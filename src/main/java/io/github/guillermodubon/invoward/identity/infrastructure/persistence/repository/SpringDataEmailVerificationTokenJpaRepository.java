package io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.EmailVerificationTokenJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data access kept inside identity infrastructure. */
public interface SpringDataEmailVerificationTokenJpaRepository
        extends JpaRepository<EmailVerificationTokenJpaEntity, UUID> {
}
