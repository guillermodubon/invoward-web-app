package io.github.guillermodubon.invoward.identity.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.UserJpaEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Maps identity application/domain models to and from their JPA representation. */
@Component
public class UserAccountPersistenceMapper {

    public UserJpaEntity toNewEntity(NewUserAccount newUserAccount) {
        Objects.requireNonNull(newUserAccount, "newUserAccount must not be null");
        return UserJpaEntity.createPendingRegistration(
                newUserAccount.displayName(),
                newUserAccount.email(),
                newUserAccount.passwordHash(),
                newUserAccount.createdAt());
    }

    public UserAccount toDomain(UserJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new UserAccount(
                entity.getId(),
                entity.getDisplayName(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.isEmailVerified(),
                entity.getStatus(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
