package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChanged;
import io.github.guillermodubon.invoward.identity.application.exception.EmailAddressConflictException;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidEmailChangeTokenException;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Atomically changes the account email and consumes all related email-change tokens. */
public class ConfirmEmailChangeTransaction {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserAccountRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ConfirmEmailChangeTransaction(
            EmailVerificationTokenRepository tokenRepository,
            UserAccountRepository userRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.tokenRepository = Objects.requireNonNull(tokenRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void confirm(UUID authenticatedUserId, String tokenHash) {
        Instant now = clock.instant();
        EmailVerificationToken token = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .filter(candidate -> isUsableFor(candidate, authenticatedUserId, now))
                .orElseThrow(InvalidEmailChangeTokenException::new);

        UserAccount currentAccount = userRepository.findById(authenticatedUserId)
                .filter(ConfirmEmailChangeTransaction::isEligible)
                .orElseThrow(InvalidEmailChangeTokenException::new);

        String targetEmail = normalizeTargetEmail(token);
        if (targetEmail.equals(currentAccount.email())
                || userRepository.existsByNormalizedEmail(targetEmail)) {
            throw new InvalidEmailChangeTokenException();
        }

        String previousEmail = currentAccount.email();
        try {
            userRepository.updateEmail(currentAccount.updateEmail(targetEmail, now));
        } catch (EmailAddressConflictException exception) {
            throw new InvalidEmailChangeTokenException();
        }

        if (!tokenRepository.markUsed(token.tokenHash(), now)) {
            throw new InvalidEmailChangeTokenException();
        }
        tokenRepository.invalidateUnusedByUserAndPurpose(
                authenticatedUserId, EmailVerificationPurpose.EMAIL_CHANGE, now);
        eventPublisher.publishEvent(new EmailChanged(authenticatedUserId, previousEmail));
    }

    private static boolean isUsableFor(
            EmailVerificationToken token,
            UUID authenticatedUserId,
            Instant now) {
        return token.purpose() == EmailVerificationPurpose.EMAIL_CHANGE
                && token.userId().equals(authenticatedUserId)
                && token.usedAt() == null
                && token.expiresAt().isAfter(now);
    }

    private static boolean isEligible(UserAccount account) {
        return account.status() == UserStatus.ACTIVE && account.emailVerified();
    }

    private static String normalizeTargetEmail(EmailVerificationToken token) {
        try {
            return UserAccount.normalizeEmail(token.targetEmail());
        } catch (IllegalArgumentException exception) {
            throw new InvalidEmailChangeTokenException();
        }
    }
}
