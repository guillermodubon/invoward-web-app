package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChangeVerificationRequested;
import io.github.guillermodubon.invoward.identity.application.exception.ReauthenticationFailedException;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Requests an email change without changing the login email before confirmation. */
public class RequestEmailChangeService {

    private final UserAccountRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final PasswordHasher passwordHasher;
    private final VerificationTokenGenerator tokenGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration tokenTtl;
    private final Duration requestCooldown;

    public RequestEmailChangeService(
            UserAccountRepository userRepository,
            EmailVerificationTokenRepository tokenRepository,
            PasswordHasher passwordHasher,
            VerificationTokenGenerator tokenGenerator,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            Duration tokenTtl,
            Duration requestCooldown) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.tokenRepository = Objects.requireNonNull(tokenRepository);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
        this.tokenTtl = Objects.requireNonNull(tokenTtl);
        this.requestCooldown = Objects.requireNonNull(requestCooldown);
    }

    @Transactional
    public void request(UUID userId, String currentPassword, String newEmail) {
        Objects.requireNonNull(userId, "userId must not be null");
        UserAccount account = userRepository.findById(userId)
                .filter(RequestEmailChangeService::isEligible)
                .orElseThrow(ReauthenticationFailedException::new);
        if (currentPassword == null || !passwordHasher.matches(currentPassword, account.passwordHash())) {
            throw new ReauthenticationFailedException();
        }

        String normalizedTarget = normalizeTargetEmail(newEmail);
        if (normalizedTarget.equals(account.email())
                || userRepository.existsByNormalizedEmail(normalizedTarget)) {
            return;
        }

        Instant now = clock.instant();
        if (isWithinCooldown(userId, now)) {
            return;
        }

        tokenRepository.invalidateUnusedByUserAndPurpose(
                userId, EmailVerificationPurpose.EMAIL_CHANGE, now);
        GeneratedVerificationToken token = tokenGenerator.generate();
        Instant expiresAt = now.plus(tokenTtl);
        tokenRepository.save(new EmailVerificationToken(
                userId, token.tokenHash(), EmailVerificationPurpose.EMAIL_CHANGE,
                normalizedTarget, expiresAt, null, now));
        eventPublisher.publishEvent(new EmailChangeVerificationRequested(
                normalizedTarget, token.rawToken(), expiresAt));
    }

    private boolean isWithinCooldown(UUID userId, Instant now) {
        return tokenRepository.findLatestCreatedAtByUserAndPurpose(
                        userId, EmailVerificationPurpose.EMAIL_CHANGE)
                .map(createdAt -> createdAt.plus(requestCooldown).isAfter(now))
                .orElse(false);
    }

    private static boolean isEligible(UserAccount account) {
        return account.status() == UserStatus.ACTIVE && account.emailVerified();
    }

    private static String normalizeTargetEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("newEmail must be provided");
        }
        return UserAccount.normalizeEmail(email);
    }
}
