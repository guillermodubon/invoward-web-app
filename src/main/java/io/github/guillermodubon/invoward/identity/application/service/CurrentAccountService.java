package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Loads current persisted account state instead of trusting the login-time session snapshot. */
@Service
public class CurrentAccountService {

    private final UserAccountRepository userAccountRepository;

    public CurrentAccountService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = Objects.requireNonNull(userAccountRepository);
    }

    @Transactional(readOnly = true)
    public CurrentAccount get(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return userAccountRepository.findById(userId)
                .map(CurrentAccount::from)
                .orElseThrow(AccountConflictException::new);
    }
}
