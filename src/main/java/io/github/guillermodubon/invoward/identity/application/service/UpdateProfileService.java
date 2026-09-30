package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Updates only the account display name and returns the persisted account projection. */
@Service
public class UpdateProfileService {

    private final UserAccountRepository userAccountRepository;
    private final Clock clock;

    public UpdateProfileService(UserAccountRepository userAccountRepository, Clock clock) {
        this.userAccountRepository = Objects.requireNonNull(userAccountRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public CurrentAccount updateDisplayName(UUID userId, String displayName) {
        Objects.requireNonNull(userId, "userId must not be null");
        UserAccount currentAccount = userAccountRepository.findById(userId)
                .orElseThrow(AccountConflictException::new);
        UserAccount updatedAccount = currentAccount.updateDisplayName(displayName, clock.instant());
        return CurrentAccount.from(userAccountRepository.updateDisplayName(updatedAccount));
    }
}
