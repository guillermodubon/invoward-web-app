package io.github.guillermodubon.invoward.identity.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.identity.application.exception.DuplicateEmailException;
import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
import io.github.guillermodubon.invoward.identity.application.exception.EmailAddressConflictException;
import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.UserJpaEntity;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.mapper.UserAccountPersistenceMapper;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataUserJpaRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
@Transactional
public class JpaUserAccountRepository implements UserAccountRepository {

    private static final String EMAIL_UNIQUE_INDEX = "users_email_lower_uq";

    private final SpringDataUserJpaRepository repository;
    private final UserAccountPersistenceMapper mapper;

    public JpaUserAccountRepository(
            SpringDataUserJpaRepository repository,
            UserAccountPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(UUID userId) {
        return repository.findById(userId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByNormalizedEmail(String normalizedEmail) {
        return repository.findByEmail(normalizedEmail).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByNormalizedEmail(String normalizedEmail) {
        return repository.existsByEmail(normalizedEmail);
    }

    @Override
    public UserAccount create(NewUserAccount newUserAccount) {
        try {
            UserJpaEntity persisted = repository.saveAndFlush(mapper.toNewEntity(newUserAccount));
            return mapper.toDomain(persisted);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniquenessViolation(exception)) {
                throw new DuplicateEmailException();
            }
            throw exception;
        }
    }

    @Override
    public UserAccount activateVerifiedRegistration(UserAccount activatedAccount) {
        if (activatedAccount.status() != UserStatus.ACTIVE || !activatedAccount.emailVerified()) {
            throw new IllegalArgumentException("Activated account must be active and verified");
        }
        return mutate(activatedAccount,
                entity -> entity.activateVerifiedRegistration(activatedAccount.updatedAt()));
    }

    @Override
    public UserAccount updateDisplayName(UserAccount updatedAccount) {
        return mutate(updatedAccount,
                entity -> entity.updateDisplayName(updatedAccount.displayName(), updatedAccount.updatedAt()));
    }

    @Override
    public UserAccount updatePasswordHash(UserAccount updatedAccount) {
        return mutate(updatedAccount,
                entity -> entity.updatePasswordHash(updatedAccount.passwordHash(), updatedAccount.updatedAt()));
    }

    @Override
    public UserAccount updateEmail(UserAccount updatedAccount) {
        if (!updatedAccount.emailVerified()) {
            throw new IllegalArgumentException("Changed email must be verified");
        }
        try {
            return mutate(updatedAccount,
                    entity -> entity.updateEmail(updatedAccount.email(), updatedAccount.updatedAt()));
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniquenessViolation(exception)) {
                throw new EmailAddressConflictException();
            }
            throw exception;
        }
    }

    private UserAccount mutate(UserAccount requestedAccount, Consumer<UserJpaEntity> mutation) {
        UserJpaEntity entity = repository.findById(requestedAccount.id())
                .orElseThrow(AccountConflictException::new);
        if (entity.getVersion() != requestedAccount.version()) {
            throw new AccountConflictException();
        }
        try {
            mutation.accept(entity);
            return mapper.toDomain(repository.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException exception) {
            throw new AccountConflictException();
        }
    }

    private static boolean isEmailUniquenessViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return EMAIL_UNIQUE_INDEX.equals(constraintViolation.getConstraintName());
            }
        }
        return false;
    }
}
