package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.RegistrationVerificationRequested;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Transaction boundary for the atomic user, token-hash, and event registration operation. */
public class RegisterUserTransaction {

    private final UserAccountRepository userAccountRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final VerificationTokenGenerator verificationTokenGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration tokenTtl;

    public RegisterUserTransaction(
            UserAccountRepository userAccountRepository,
            EmailVerificationTokenRepository verificationTokenRepository,
            VerificationTokenGenerator verificationTokenGenerator,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            Duration tokenTtl) {
        this.userAccountRepository = Objects.requireNonNull(userAccountRepository);
        this.verificationTokenRepository = Objects.requireNonNull(verificationTokenRepository);
        this.verificationTokenGenerator = Objects.requireNonNull(verificationTokenGenerator);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
        this.tokenTtl = Objects.requireNonNull(tokenTtl);
    }

    @Transactional
    public void createPendingRegistration(String displayName, String normalizedEmail, String passwordHash) {
        Instant now = clock.instant();
        UserAccount user = userAccountRepository.create(
                new NewUserAccount(displayName, normalizedEmail, passwordHash, now));
        GeneratedVerificationToken token = verificationTokenGenerator.generate();
        Instant expiresAt = now.plus(tokenTtl);

        verificationTokenRepository.saveRegistrationToken(
                user.id(), normalizedEmail, token.tokenHash(), now, expiresAt);
        eventPublisher.publishEvent(new RegistrationVerificationRequested(
                normalizedEmail, token.rawToken(), expiresAt));
    }
}
