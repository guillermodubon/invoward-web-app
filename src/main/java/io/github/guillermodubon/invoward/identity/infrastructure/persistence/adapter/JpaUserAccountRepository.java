package io.github.guillermodubon.invoward.identity.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.identity.application.exception.DuplicateEmailException;
import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity.UserJpaEntity;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.mapper.UserAccountPersistenceMapper;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataUserJpaRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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

    private static boolean isEmailUniquenessViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return EMAIL_UNIQUE_INDEX.equals(constraintViolation.getConstraintName());
            }
        }
        return false;
    }
}
