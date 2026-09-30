package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordChanged;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidPasswordResetTokenException;
import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Atomically changes the credential and consumes the locked reset token. */
public class ResetPasswordTransaction {

    private final PasswordResetTokenRepository tokenRepository;
    private final UserAccountRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ResetPasswordTransaction(
            PasswordResetTokenRepository tokenRepository,
            UserAccountRepository userRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.tokenRepository = Objects.requireNonNull(tokenRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void complete(String tokenHash, String encodedPassword) {
        Instant now = clock.instant();
        PasswordResetToken token = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .filter(candidate -> isUsable(candidate, now))
                .orElseThrow(InvalidPasswordResetTokenException::new);
        UserAccount user = userRepository.findById(token.userId())
                .filter(ResetPasswordTransaction::isEligible)
                .orElseThrow(InvalidPasswordResetTokenException::new);

        userRepository.updatePasswordHash(user.updatePasswordHash(encodedPassword, now));
        if (!tokenRepository.markUsed(tokenHash, now)) {
            throw new InvalidPasswordResetTokenException();
        }
        tokenRepository.invalidateUnusedByUser(user.id(), now);
        eventPublisher.publishEvent(new PasswordChanged(user.id(), user.email()));
    }

    private static boolean isUsable(PasswordResetToken token, Instant now) {
        return token.usedAt() == null && token.expiresAt().isAfter(now);
    }

    private static boolean isEligible(UserAccount user) {
        return user.status() == UserStatus.ACTIVE && user.emailVerified();
    }
}
