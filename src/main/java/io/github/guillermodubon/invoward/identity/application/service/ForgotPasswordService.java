package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordResetRequested;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Issues password reset tokens only for active, email-verified accounts. */
public class ForgotPasswordService {

    private final UserAccountRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final VerificationTokenGenerator tokenGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration tokenTtl;
    private final Duration requestCooldown;

    public ForgotPasswordService(
            UserAccountRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            VerificationTokenGenerator tokenGenerator,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            Duration tokenTtl,
            Duration requestCooldown) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.tokenRepository = Objects.requireNonNull(tokenRepository);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
        this.tokenTtl = Objects.requireNonNull(tokenTtl);
        this.requestCooldown = Objects.requireNonNull(requestCooldown);
    }

    @Transactional
    public void request(String email) {
        String normalizedEmail = UserAccount.normalizeEmail(email);
        UserAccount user = userRepository.findByNormalizedEmail(normalizedEmail).orElse(null);
        if (!isEligible(user)) {
            return;
        }

        Instant now = clock.instant();
        if (isWithinCooldown(user.id(), now)) {
            return;
        }

        tokenRepository.invalidateUnusedByUser(user.id(), now);
        GeneratedVerificationToken token = tokenGenerator.generate();
        Instant expiresAt = now.plus(tokenTtl);
        tokenRepository.save(new PasswordResetToken(
                user.id(), token.tokenHash(), expiresAt, null, now));
        eventPublisher.publishEvent(new PasswordResetRequested(user.email(), token.rawToken(), expiresAt));
    }

    private boolean isWithinCooldown(UUID userId, Instant now) {
        return tokenRepository.findLatestCreatedAtByUser(userId)
                .map(createdAt -> createdAt.plus(requestCooldown).isAfter(now))
                .orElse(false);
    }

    private static boolean isEligible(UserAccount user) {
        return user != null
                && user.status() == UserStatus.ACTIVE
                && user.emailVerified();
    }
}
