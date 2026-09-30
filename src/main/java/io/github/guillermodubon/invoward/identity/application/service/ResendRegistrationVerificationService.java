package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.RegistrationVerificationRequested;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
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

/** Rotates a pending account's registration token while preserving generic public behavior. */
public class ResendRegistrationVerificationService {

    private final UserAccountRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final VerificationTokenGenerator tokenGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration tokenTtl;
    private final Duration resendCooldown;

    public ResendRegistrationVerificationService(
            UserAccountRepository userRepository,
            EmailVerificationTokenRepository tokenRepository,
            VerificationTokenGenerator tokenGenerator,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            Duration tokenTtl,
            Duration resendCooldown) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.tokenRepository = Objects.requireNonNull(tokenRepository);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
        this.tokenTtl = Objects.requireNonNull(tokenTtl);
        this.resendCooldown = Objects.requireNonNull(resendCooldown);
    }

    @Transactional
    public void resend(String email) {
        String normalizedEmail = UserAccount.normalizeEmail(email);
        UserAccount user = userRepository.findByNormalizedEmail(normalizedEmail).orElse(null);
        if (!isEligible(user)) {
            return;
        }

        Instant now = clock.instant();
        if (isWithinCooldown(user.id(), now)) {
            return;
        }

        tokenRepository.invalidateUnusedByUserAndPurpose(
                user.id(), EmailVerificationPurpose.REGISTRATION, now);
        GeneratedVerificationToken token = tokenGenerator.generate();
        Instant expiresAt = now.plus(tokenTtl);
        tokenRepository.save(new EmailVerificationToken(
                user.id(), token.tokenHash(), EmailVerificationPurpose.REGISTRATION,
                user.email(), expiresAt, null, now));
        eventPublisher.publishEvent(new RegistrationVerificationRequested(
                user.email(), token.rawToken(), expiresAt));
    }

    private boolean isWithinCooldown(UUID userId, Instant now) {
        return tokenRepository.findLatestCreatedAtByUserAndPurpose(
                        userId, EmailVerificationPurpose.REGISTRATION)
                .map(createdAt -> createdAt.plus(resendCooldown).isAfter(now))
                .orElse(false);
    }

    private static boolean isEligible(UserAccount user) {
        return user != null
                && user.status() == UserStatus.PENDING_VERIFICATION
                && !user.emailVerified();
    }
}
