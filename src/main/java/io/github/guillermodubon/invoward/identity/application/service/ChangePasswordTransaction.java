package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordChanged;
import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Atomically updates credentials and invalidates recovery tokens before publishing a post-commit event. */
public class ChangePasswordTransaction {

    private final UserAccountRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ChangePasswordTransaction(
            UserAccountRepository userRepository,
            PasswordResetTokenRepository resetTokenRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.resetTokenRepository = Objects.requireNonNull(resetTokenRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void complete(UUID userId, long expectedVersion, String encodedPassword) {
        Instant now = clock.instant();
        UserAccount currentAccount = userRepository.findById(userId)
                .filter(ChangePasswordTransaction::isEligible)
                .orElseThrow(AccountConflictException::new);
        if (currentAccount.version() != expectedVersion) {
            throw new AccountConflictException();
        }

        userRepository.updatePasswordHash(currentAccount.updatePasswordHash(encodedPassword, now));
        resetTokenRepository.invalidateUnusedByUser(userId, now);
        eventPublisher.publishEvent(new PasswordChanged(userId, currentAccount.email()));
    }

    private static boolean isEligible(UserAccount account) {
        return account.status() == UserStatus.ACTIVE && account.emailVerified();
    }
}
